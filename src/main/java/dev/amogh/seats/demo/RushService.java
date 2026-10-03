package dev.amogh.seats.demo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.amogh.seats.reservation.ReservationService;
import dev.amogh.seats.reservation.ReserveResult;
import dev.amogh.seats.reservation.SeatRequest;
import dev.amogh.seats.show.Section;
import dev.amogh.seats.show.Show;
import dev.amogh.seats.show.ShowCatalog;
import dev.amogh.seats.web.ApiException;

/**
 * "Simulate rush": a crowd of bots descends on a visitor's demo show so they can
 * watch the seat map fill up and see exactly one winner per seat.
 *
 * Bots call {@link ReservationService#reserve} directly: the same transaction,
 * locks, idempotency and limits as an HTTP request, minus the network. Most go
 * for the best rows, some pick anywhere, some want standing places; a few retry
 * with the same idempotency key, and some ask for partial bookings. On a
 * hold-mode show most bots "pay" (confirm) and some walk away, so the visitor
 * can also watch abandoned holds expire.
 *
 * Bounded so a demo can't hurt real traffic: at most 2 rushes service-wide,
 * one per show, and at most {@value #IN_FLIGHT} bot requests in flight per rush
 * (well under the connection pool).
 */
@Service
public class RushService {

    private static final Logger log = LoggerFactory.getLogger(RushService.class);
    static final int IN_FLIGHT = 12;
    static final int MAX_CONCURRENT_RUSHES = 2;
    private static final long SPREAD_MS = 6000;

    private final ReservationService reservations;
    private final ShowCatalog catalog;
    private final DemoProperties props;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_RUSHES);
    private final Cache<String, Rush> rushes = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(1000)
            .build();

    public RushService(ReservationService reservations, ShowCatalog catalog, DemoProperties props) {
        this.reservations = reservations;
        this.catalog = catalog;
        this.props = props;
    }

    /** Live progress, as GET /demo/shows/{id}/rush returns it. */
    public record RushStatus(String showId, int bots, boolean running, long elapsedMs, int attempts,
                             int reservations, int seats, int paid, int abandoned, int replays,
                             Map<String, Integer> declined, int errors) {
    }

    public RushStatus start(String showId, int bots) {
        Show show = catalog.require(showId);
        if (!"demo".equals(show.kind())) {
            throw ApiException.forbidden("rush_not_allowed",
                    "Rushes only run on your own demo shows (POST /demo/shows), not on featured or API shows");
        }
        if (bots < 1 || bots > props.maxRushBots()) {
            throw ApiException.badRequest("invalid_bots", "bots must be between 1 and " + props.maxRushBots());
        }
        Rush existing = rushes.getIfPresent(show.id());
        if (existing != null && existing.running.get()) {
            throw ApiException.conflict("rush_running", "A rush is already running on this show");
        }
        if (!slots.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rush_busy",
                    "Two rushes are already running; try again in a few seconds", null);
        }
        var rush = new Rush(show, bots);
        rushes.put(show.id(), rush);
        Thread.ofVirtual().name("rush-" + show.id()).start(() -> {
            try {
                rush.run();
            } finally {
                slots.release();
            }
        });
        return rush.status();
    }

    public RushStatus status(String showId) {
        Rush rush = rushes.getIfPresent(showId);
        if (rush == null) {
            throw ApiException.notFound("no_rush", "No rush has run on this show recently");
        }
        return rush.status();
    }

    private final class Rush {
        final Show show;
        final int bots;
        final String tag = UUID.randomUUID().toString().substring(0, 6);
        final SeatPicker picker;
        final long startedAt = System.nanoTime();
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile long finishedAt;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger reserved = new AtomicInteger();
        final AtomicInteger seats = new AtomicInteger();
        final AtomicInteger paid = new AtomicInteger();
        final AtomicInteger abandoned = new AtomicInteger();
        final AtomicInteger replays = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final Map<String, AtomicInteger> declined = new ConcurrentHashMap<>();
        final Semaphore inFlight = new Semaphore(IN_FLIGHT);

        Rush(Show show, int bots) {
            this.show = show;
            this.bots = bots;
            this.picker = new SeatPicker(show);
        }

        void run() {
            log.atInfo().addKeyValue("event", "rush_started").addKeyValue("show_id", show.id())
                    .addKeyValue("bots", bots).log("rush on {} with {} bots", show.id(), bots);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < bots; i++) {
                    int bot = i;
                    pool.submit(() -> bot(bot));
                }
            } finally {
                finishedAt = System.nanoTime();
                running.set(false);
                log.atInfo().addKeyValue("event", "rush_finished").addKeyValue("show_id", show.id())
                        .addKeyValue("reservations", reserved.get()).addKeyValue("seats", seats.get())
                        .addKeyValue("errors", errors.get()).log("rush on {} finished", show.id());
            }
        }

        void bot(int i) {
            var rnd = ThreadLocalRandom.current();
            // Front-loaded arrivals: most of the crowd hits in the first couple of seconds.
            sleep((long) (SPREAD_MS * Math.pow(rnd.nextDouble(), 2.2)));
            String user = "bot-" + tag + "-" + i;
            SeatRequest ask = picker.pick(rnd);
            String key = rnd.nextInt(10) == 0 ? "rush-" + tag + "-" + i : null;
            try {
                inFlight.acquire();
                ReserveResult result;
                try {
                    attempts.incrementAndGet();
                    result = reservations.reserve(user, show.id(), ask, key);
                    if (key != null) { // the "client timed out and retried" bot
                        attempts.incrementAndGet();
                        if (reservations.reserve(user, show.id(), ask, key) instanceof ReserveResult.Replayed) {
                            replays.incrementAndGet();
                        }
                    }
                } finally {
                    inFlight.release();
                }
                switch (result) {
                    case ReserveResult.Reserved r -> {
                        reserved.incrementAndGet();
                        seats.addAndGet(r.reservation().seats().size());
                        if (r.reservation().status().equals("held")) {
                            payOrWalkAway(user, r.reservation().reservationId(), rnd);
                        }
                    }
                    case ReserveResult.Declined d ->
                            declined.computeIfAbsent(d.reason().code(), k -> new AtomicInteger()).incrementAndGet();
                    case ReserveResult.Replayed p -> replays.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                errors.incrementAndGet();
                log.warn("rush bot {} failed: {}", user, e.getMessage());
            }
        }

        /** Most bots pay within a couple of seconds; about one in six abandons the hold. */
        void payOrWalkAway(String user, String reservationId, ThreadLocalRandom rnd) throws InterruptedException {
            if (rnd.nextInt(6) == 0) {
                abandoned.incrementAndGet();
                return;
            }
            sleep(300 + rnd.nextLong(1700));
            inFlight.acquire();
            try {
                reservations.confirm(user, reservationId);
                paid.incrementAndGet();
            } finally {
                inFlight.release();
            }
        }

        RushStatus status() {
            long end = running.get() ? System.nanoTime() : finishedAt;
            var reasons = new TreeMap<String, Integer>();
            declined.forEach((k, v) -> reasons.put(k, v.get()));
            return new RushStatus(show.id(), bots, running.get(), (end - startedAt) / 1_000_000, attempts.get(),
                    reserved.get(), seats.get(), paid.get(), abandoned.get(), replays.get(), reasons, errors.get());
        }
    }

    /**
     * Where a bot wants to sit. 55% go for the front rows of the priciest seated
     * section (the hot seats), 30% pick a block anywhere, 15% want standing
     * places if the show has any. One in five accepts a partial booking.
     */
    static final class SeatPicker {
        private final List<List<String>> hotRows = new ArrayList<>();
        private final List<List<String>> allRows = new ArrayList<>();
        private final List<Section> standing;

        SeatPicker(Show show) {
            var seated = show.sections().stream().filter(s -> !s.standing()).toList();
            standing = show.sections().stream().filter(Section::standing).toList();
            // The hot seats: front two rows of the first real seating block nearest the
            // stage (sections are in layout order; tiny boxes and lounges don't count).
            Section hot = seated.stream().filter(s -> s.capacity() >= 50).findFirst()
                    .orElse(seated.isEmpty() ? null : seated.getFirst());
            for (Section section : seated) {
                List<List<String>> rows = rowsOf(show, section);
                allRows.addAll(rows);
                if (section == hot) {
                    rows.stream().limit(2).forEach(hotRows::add);
                }
            }
        }

        private static List<List<String>> rowsOf(Show show, Section section) {
            var rows = new ArrayList<List<String>>();
            if (section.rows() == null) { // a flat show: one long "row" in creation order
                rows.add(show.seatSections().entrySet().stream()
                        .filter(e -> e.getValue().equals(section.code())).map(Map.Entry::getKey).toList());
                return rows;
            }
            for (var row : section.rows()) {
                String name = row.get("row").asString();
                int count = row.get("seats").asInt();
                var labels = new ArrayList<String>(count);
                for (int n = 1; n <= count; n++) {
                    labels.add(section.code() + "-" + name + n);
                }
                rows.add(labels);
            }
            return rows;
        }

        SeatRequest pick(ThreadLocalRandom rnd) {
            boolean partial = rnd.nextInt(5) == 0;
            double roll = rnd.nextDouble();
            if ((roll >= 0.85 || allRows.isEmpty()) && !standing.isEmpty()) {
                Section s = standing.get(rnd.nextInt(standing.size()));
                return new SeatRequest.Standing(s.code(), 1 + rnd.nextInt(4), partial);
            }
            boolean hot = roll < 0.55 && !hotRows.isEmpty();
            List<String> row = hot ? hotRows.get(rnd.nextInt(hotRows.size())) : allRows.get(rnd.nextInt(allRows.size()));
            int want = Math.min(row.size(), hot ? 1 + rnd.nextInt(3) : 1 + rnd.nextInt(4));
            // Hot-seat bots crowd the middle of the row; others start anywhere.
            int start = hot
                    ? (int) Math.round(row.size() / 2.0 - want / 2.0 + rnd.nextGaussian() * row.size() / 8.0)
                    : rnd.nextInt(row.size());
            start = Math.max(0, Math.min(row.size() - want, start));
            return new SeatRequest.Named(List.copyOf(row.subList(start, start + want)), partial);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

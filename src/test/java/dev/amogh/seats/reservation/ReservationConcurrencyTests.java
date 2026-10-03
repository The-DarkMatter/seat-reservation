package dev.amogh.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReserveResult.Declined;
import dev.amogh.seats.reservation.ReserveResult.Replayed;
import dev.amogh.seats.reservation.ReserveResult.Reserved;
import dev.amogh.seats.show.CreateShowRequest;
import dev.amogh.seats.show.ShowService;

/**
 * Races against real InnoDB. Every test releases all its threads at once from
 * a latch so the requests genuinely collide, then checks both the API outcomes
 * and the rows in MySQL.
 */
@IntegrationTest
class ReservationConcurrencyTests {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;
    @Autowired
    JdbcClient jdbc;

    private String newShow(int seats, int perUserLimit) {
        var labels = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        return shows.create(new CreateShowRequest("race", labels, 25000L, perUserLimit, null)).id();
    }

    /** Runs n tasks concurrently (virtual threads, common start gate) and returns their results. */
    private static <T> List<T> race(int n, IntFunction<T> task) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.apply(idx);
                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var f : futures) {
                results.add(f.get()); // rethrows any exception: none are allowed
            }
            return results;
        }
    }

    private static long count(List<ReserveResult> results, Class<? extends ReserveResult> type) {
        return results.stream().filter(type::isInstance).count();
    }

    private static long declined(List<ReserveResult> results, DeclineReason reason) {
        return results.stream().filter(r -> r instanceof Declined d && d.reason() == reason).count();
    }

    @Test
    void fiveHundredBuyersOneSeatExactlyOneWinner() throws Exception {
        String show = newShow(10, 4);

        var results = race(500, i -> reservations.reserve("hot-user-" + i, show, List.of("A1"), "k-" + i));

        assertThat(count(results, Reserved.class)).isEqualTo(1);
        assertThat(declined(results, DeclineReason.SEAT_TAKEN)).isEqualTo(499);

        var winner = results.stream().filter(Reserved.class::isInstance).map(Reserved.class::cast)
                .findFirst().orElseThrow();
        var seat = jdbc.sql("SELECT status, user_id, reservation_id FROM seats WHERE show_id = ? AND label = 'A1'")
                .param(show).query().singleRow();
        assertThat(seat.get("status")).isEqualTo("confirmed");
        assertThat(seat.get("user_id")).isEqualTo(winner.reservation().userId());
        assertThat(seat.get("reservation_id")).isEqualTo(winner.reservation().reservationId());
        assertThat(rows("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).isEqualTo(1);
    }

    @Test
    void oneUserFiringTenParallelReservesEndsWithAtMostTheLimit() throws Exception {
        String show = newShow(20, 4);

        var results = race(10, i -> reservations.reserve("greedy", show, List.of("A" + (i + 1)), "g-" + i));

        assertThat(count(results, Reserved.class)).isEqualTo(4);
        assertThat(declined(results, DeclineReason.PER_USER_LIMIT)).isEqualTo(6);
        assertThat(rows("SELECT COUNT(*) FROM seats WHERE show_id = ? AND user_id = 'greedy'", show)).isEqualTo(4);
    }

    @Test
    void perUserLimitCountsSeatsNotRequests() throws Exception {
        String show = newShow(20, 4);

        // 6 parallel two-seat requests from one user: at most 2 can fit under a limit of 4.
        var results = race(6, i -> reservations.reserve("pairs", show,
                List.of("A" + (2 * i + 1), "A" + (2 * i + 2)), "p-" + i));

        assertThat(count(results, Reserved.class)).isEqualTo(2);
        assertThat(rows("SELECT COUNT(*) FROM seats WHERE show_id = ? AND user_id = 'pairs'", show)).isEqualTo(4);
    }

    @Test
    void fiftyRetriesOfOneKeyReserveExactlyOnce() throws Exception {
        String show = newShow(10, 4);

        var results = race(50, i -> reservations.reserve("retrier", show, List.of("A3", "A4"), "same-key"));

        assertThat(count(results, Reserved.class)).isEqualTo(1);
        assertThat(count(results, Replayed.class)).isEqualTo(49);
        String original = results.stream().filter(Reserved.class::isInstance).findFirst().orElseThrow().body();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.httpStatus()).isEqualTo(201);
            assertThat(r.body()).isEqualTo(original);
        });
        assertThat(rows("SELECT COUNT(*) FROM reservations WHERE show_id = ?", show)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentSeatsIsRejectedEvenInParallel() throws Exception {
        String show = newShow(10, 4);

        var results = race(20, i -> reservations.reserve("mixer", show,
                List.of(i % 2 == 0 ? "A1" : "A2"), "mixed-key"));

        // Whichever body won the key, every request for the other body is a key reuse.
        assertThat(count(results, Reserved.class)).isEqualTo(1);
        assertThat(declined(results, DeclineReason.IDEMPOTENCY_KEY_REUSE)).isEqualTo(10);
        assertThat(count(results, Replayed.class)).isEqualTo(9);
        assertThat(rows("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status <> 'available'", show)).isEqualTo(1);
    }

    @Test
    void retryOfADeclineReplaysTheDecline() {
        String show = newShow(5, 4);
        reservations.reserve("first", show, List.of("A1"), null);

        var declined = reservations.reserve("second", show, List.of("A1"), "late-key");
        var retried = reservations.reserve("second", show, List.of("A1"), "late-key");

        assertThat(declined).isInstanceOf(Declined.class);
        assertThat(retried).isInstanceOf(Replayed.class);
        assertThat(retried.httpStatus()).isEqualTo(409);
        assertThat(retried.body()).isEqualTo(declined.body());
    }

    @Test
    void oppositeOrderMultiSeatRequestsNeverDeadlockOrSplit() throws Exception {
        String show = newShow(12, 50);

        // 300 users grab overlapping pairs, each pair listed in random order.
        var results = race(300, i -> {
            int a = ThreadLocalRandom.current().nextInt(1, 12);
            var pair = new ArrayList<>(List.of("A" + a, "A" + (a + 1)));
            Collections.shuffle(pair);
            return reservations.reserve("pair-user-" + i, show, pair, null);
        });

        assertThat(count(results, Reserved.class) + declined(results, DeclineReason.SEAT_TAKEN)).isEqualTo(300);

        // All-or-nothing: every winning reservation owns both of its seats, and no seat has two owners.
        var owners = new HashMap<String, String>();
        for (var r : results) {
            if (r instanceof Reserved won) {
                for (String seat : won.reservation().seats()) {
                    assertThat(owners.put(seat, won.reservation().reservationId())).isNull();
                    String owner = jdbc.sql("SELECT reservation_id FROM seats WHERE show_id = ? AND label = ?")
                            .params(show, seat).query(String.class).single();
                    assertThat(owner).isEqualTo(won.reservation().reservationId());
                }
            }
        }
        assertThat(rows("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status <> 'available'", show))
                .isEqualTo(owners.size());
    }

    @Test
    void partialRequestIsAllOrNothing() {
        String show = newShow(5, 4);
        reservations.reserve("early", show, List.of("A2"), null);

        var result = reservations.reserve("late", show, List.of("A1", "A2"), null);

        assertThat(result).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.SEAT_TAKEN));
        String a1 = jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND label = 'A1'")
                .param(show).query(String.class).single();
        assertThat(a1).isEqualTo("available");
    }

    @Test
    void reconciliationHoldsDuringAStampede() throws Exception {
        String show = newShow(200, 4);
        var stop = new AtomicBoolean();
        var checks = new AtomicInteger();
        var violations = Collections.synchronizedList(new ArrayList<Map<String, Object>>());

        Thread poller = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                var counts = shows.get(show).counts();
                int sum = counts.available() + counts.held() + counts.confirmed();
                checks.incrementAndGet();
                if (sum != 200) {
                    violations.add(Map.of("sum", sum));
                }
            }
        });

        // 1,800 requests skewed toward the first 20 "good" seats, 1-3 seats each,
        // some users sending several, plus 200 exact retries (same user, seats, key).
        record Req(String user, List<String> seats, String key) { }
        var rnd = ThreadLocalRandom.current();
        var requests = new ArrayList<Req>();
        for (int i = 0; i < 1800; i++) {
            int n = rnd.nextInt(1, 4);
            var wanted = new ArrayList<String>();
            while (wanted.size() < n) {
                int seat = rnd.nextDouble() < 0.7 ? rnd.nextInt(1, 21) : rnd.nextInt(1, 201);
                if (!wanted.contains("A" + seat)) {
                    wanted.add("A" + seat);
                }
            }
            requests.add(new Req("u-" + (i % 1500), wanted, "s-" + i));
        }
        for (int i = 0; i < 200; i++) {
            requests.add(requests.get(i));
        }
        var results = race(requests.size(), i -> {
            var r = requests.get(i);
            return reservations.reserve(r.user(), show, r.seats(), r.key());
        });
        stop.set(true);
        poller.join();

        assertThat(violations).isEmpty();
        assertThat(checks.get()).isPositive();
        int confirmedSeats = rows("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", show);
        int reservedSeats = results.stream().filter(Reserved.class::isInstance)
                .mapToInt(r -> ((Reserved) r).reservation().seats().size()).sum();
        assertThat(confirmedSeats).isEqualTo(reservedSeats);
        assertThat(rows("SELECT COUNT(*) FROM (SELECT user_id FROM seats WHERE show_id = ? AND user_id IS NOT NULL"
                + " GROUP BY user_id HAVING COUNT(*) > 4) over_limit", show)).isZero();
    }

    private int rows(String sql, String showId) {
        return jdbc.sql(sql).param(showId).query(Integer.class).single();
    }
}

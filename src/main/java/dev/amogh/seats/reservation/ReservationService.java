package dev.amogh.seats.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import dev.amogh.seats.obs.ReservationMetrics;
import dev.amogh.seats.reservation.ReserveResult.Declined;
import dev.amogh.seats.reservation.ReserveResult.Replayed;
import dev.amogh.seats.reservation.ReserveResult.Reserved;
import dev.amogh.seats.show.Show;
import dev.amogh.seats.show.ShowCatalog;
import dev.amogh.seats.web.ApiException;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reserve, in one READ COMMITTED transaction:
 *
 * <ol>
 *   <li>claim the idempotency key (the PK makes a second claim impossible);</li>
 *   <li>SAVEPOINT;</li>
 *   <li>fast path: if a requested seat is visibly taken (plain read, no lock), decline;</li>
 *   <li>lock this user's (show, user) mutex row and check the per-user limit;</li>
 *   <li>claim each seat, in sorted order, with a conditional UPDATE;</li>
 *   <li>insert the reservation, store the response on the key, COMMIT.</li>
 * </ol>
 *
 * On a decline: roll back to the savepoint (undoing any seats claimed so far,
 * which is what makes multi-seat requests all-or-nothing), store the 409 on the
 * key, COMMIT. A retry with the same key then gets the same answer.
 *
 * Lock order is always: idempotency row, then user mutex, then seats in
 * ascending label order. Everyone takes locks in the same order, so there is
 * no cycle and no deadlock. If InnoDB still reports one (or a lock-wait
 * timeout), the whole transaction is retried, which is safe because the
 * idempotency row rolls back with it.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    static final int MAX_SEATS_PER_REQUEST = 50;
    static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_ATTEMPTS = 4;

    private final ReservationRepository reservations;
    private final IdempotencyRepository idempotency;
    private final ShowCatalog catalog;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final ReservationMetrics metrics;

    public ReservationService(ReservationRepository reservations, IdempotencyRepository idempotency,
                              ShowCatalog catalog, PlatformTransactionManager txManager, JsonMapper json,
                              ReservationMetrics metrics) {
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.catalog = catalog;
        this.json = json;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(15);
    }

    public ReserveResult reserve(String userId, String showId, List<String> requestedSeats, String idempotencyKey) {
        Show show = catalog.require(showId);
        List<String> seats = validateSeats(show, requestedSeats);
        String key = validateKey(idempotencyKey);
        String fingerprint = fingerprint(show.id(), seats);

        Timer.Sample timer = metrics.startTimer();
        long start = System.nanoTime();
        ReserveResult result = withRetry(() ->
                tx.execute(status -> reserveOnce(status, userId, show, seats, key, fingerprint)));
        record(result, userId, show, seats, timer, start);
        return result;
    }

    /** Runs after commit: counters and the log line only ever describe what the database kept. */
    private void record(ReserveResult result, String userId, Show show, List<String> seats,
                        Timer.Sample timer, long start) {
        String outcome;
        String reason = null;
        String reservationId = null;
        switch (result) {
            case Reserved r -> {
                outcome = r.reservation().status();
                reservationId = r.reservation().reservationId();
                metrics.reserved(r.reservation().status(), seats.size());
            }
            case Declined d -> {
                outcome = "declined";
                reason = d.reason().code();
                metrics.declined(d.reason());
            }
            case Replayed p -> {
                outcome = "replayed";
                reason = DeclineReason.IDEMPOTENT_REPLAY.code();
                metrics.declined(DeclineReason.IDEMPOTENT_REPLAY);
            }
        }
        metrics.stopTimer(timer, outcome);
        log.atInfo()
                .addKeyValue("event", "reserve")
                .addKeyValue("outcome", outcome)
                .addKeyValue("reason", reason)
                .addKeyValue("show_id", show.id())
                .addKeyValue("user_id", userId)
                .addKeyValue("seats", String.join(",", seats))
                .addKeyValue("reservation_id", reservationId)
                .addKeyValue("http_status", result.httpStatus())
                .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                .log("reserve {} {}", outcome, reason == null ? "" : reason);
    }

    private ReserveResult reserveOnce(TransactionStatus status, String userId, Show show, List<String> seats,
                                      String key, String fingerprint) {
        if (key != null && !idempotency.tryClaim(userId, key, fingerprint)) {
            var stored = idempotency.find(userId, key)
                    .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
            if (!stored.requestHash().equals(fingerprint)) {
                return declined(DeclineReason.IDEMPOTENCY_KEY_REUSE,
                        "This idempotency key was already used for a different request", Map.of());
            }
            return new Replayed(stored.httpStatus(), stored.response());
        }

        Object savepoint = status.createSavepoint();
        ReserveResult result = claim(userId, show, seats);
        if (result instanceof Declined) {
            status.rollbackToSavepoint(savepoint);
        } else {
            status.releaseSavepoint(savepoint);
        }
        if (key != null) {
            idempotency.complete(userId, key, result.httpStatus(), result.body());
        }
        return result;
    }

    private ReserveResult claim(String userId, Show show, List<String> seats) {
        var visiblyTaken = reservations.firstUnavailable(show.id(), seats);
        if (visiblyTaken.isPresent()) {
            return seatTaken(visiblyTaken.get());
        }

        reservations.lockUser(show.id(), userId);
        int alreadyHeld = reservations.countLiveSeats(show.id(), userId);
        if (alreadyHeld + seats.size() > show.perUserLimit()) {
            var details = new LinkedHashMap<String, Object>();
            details.put("per_user_limit", show.perUserLimit());
            details.put("seats_held", alreadyHeld);
            details.put("seats_requested", seats.size());
            return declined(DeclineReason.PER_USER_LIMIT,
                    "This would exceed the limit of " + show.perUserLimit() + " seats per user", details);
        }

        String seatStatus = show.usesHolds() ? "held" : "confirmed";
        LocalDateTime heldUntil = show.usesHolds() ? reservations.dbNowPlusSeconds(show.holdTtlSeconds()) : null;
        String reservationId = UUID.randomUUID().toString();

        // Sorted order is the deadlock-avoidance rule: two requests for {A12, A13}
        // and {A13, A12} both lock A12 first, so neither can hold what the other needs.
        for (String seat : seats.stream().sorted().toList()) {
            if (!reservations.claimSeat(show.id(), seat, reservationId, userId, seatStatus, heldUntil)) {
                return seatTaken(seat);
            }
        }

        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
        reservations.insertReservation(reservationId, show.id(), userId, seats, amount, seatStatus, heldUntil);
        var view = new ReservationView(reservationId, show.id(), userId, seats, amount, seatStatus,
                heldUntil == null ? null : heldUntil.toInstant(ZoneOffset.UTC));
        return new Reserved(view, json.writeValueAsString(view));
    }

    public ReservationView get(String userId, String reservationId) {
        var row = reservations.find(reservationId).orElseThrow(ReservationService::reservationNotFound);
        requireOwner(row, userId);
        return toView(row);
    }

    /** What a cancel/confirm/expiry did, so the caller can count it after commit. */
    public record Transition(ReservationView reservation, int seatsMoved, boolean changed) {
    }

    /**
     * Owner-only. Lock order: the reservation row, then its seats (label order).
     * Cancelling twice is a no-op that returns the same state. The release
     * itself is guarded by reservation_id, so it can only ever free seats this
     * reservation still owns; a seat that expired and was re-booked by someone
     * else is untouched.
     */
    public Transition cancel(String userId, String reservationId) {
        Transition t = withRetry(() -> tx.execute(status -> {
            var row = reservations.findForUpdate(reservationId).orElseThrow(ReservationService::reservationNotFound);
            requireOwner(row, userId);
            if (row.status().equals("cancelled") || row.status().equals("expired")) {
                return new Transition(toView(row), 0, false);
            }
            int released = reservations.releaseSeats(row.id());
            reservations.setStatus(row.id(), "cancelled");
            return new Transition(toView(row).withStatus("cancelled"), released, true);
        }));
        if (t.changed()) {
            metrics.cancelled(t.seatsMoved());
            logTransition("cancel", t);
        }
        return t;
    }

    /**
     * Owner-only: turns a live hold into a sale (the "payment succeeded" step).
     * Idempotent: confirming a confirmed reservation returns it unchanged, so a
     * retried payment callback can never sell or charge twice. A hold that ran
     * out is 409 hold_expired; who wins a confirm-vs-rebook race at the expiry
     * instant is decided by the database clock inside the UPDATE.
     */
    public Transition confirm(String userId, String reservationId) {
        Transition t = withRetry(() -> tx.execute(status -> {
            var row = reservations.findForUpdate(reservationId).orElseThrow(ReservationService::reservationNotFound);
            requireOwner(row, userId);
            return switch (row.status()) {
                case "confirmed" -> new Transition(toView(row), 0, false);
                case "cancelled" -> throw ApiException.conflict("reservation_cancelled",
                        "This reservation was cancelled");
                case "expired" -> throw holdExpired();
                case "held" -> {
                    int confirmed = reservations.confirmSeats(row.id());
                    if (confirmed != row.seats().size()) {
                        // Throwing rolls back the transaction, so a partial confirm can't stick.
                        throw holdExpired();
                    }
                    reservations.setStatus(row.id(), "confirmed");
                    yield new Transition(toView(row).withStatus("confirmed"), confirmed, true);
                }
                default -> throw new IllegalStateException("unknown reservation status " + row.status());
            };
        }));
        if (t.changed()) {
            metrics.holdConfirmed(t.seatsMoved());
            logTransition("confirm", t);
        }
        return t;
    }

    /**
     * Expires ONE overdue hold, if any. The sweeper calls this in a loop. One
     * reservation per transaction keeps the lock order identical to cancel's
     * (reservation row, then its seats in label order). SKIP LOCKED steps
     * around holds that a confirm or cancel is working on right now.
     * Returns null when there's nothing left to expire.
     */
    public Transition expireOneOverdueHold() {
        Transition t = withRetry(() -> tx.execute(status -> {
            var ids = reservations.lockExpiredHolds(1);
            if (ids.isEmpty()) {
                return null;
            }
            var row = reservations.find(ids.getFirst()).orElseThrow();
            int released = reservations.releaseExpiredSeats(row.id());
            reservations.setStatus(row.id(), "expired");
            return new Transition(toView(row).withStatus("expired"), released, true);
        }));
        if (t != null) {
            metrics.expired(t.seatsMoved());
            logTransition("expire", t);
        }
        return t;
    }

    private static void logTransition(String event, Transition t) {
        log.atInfo()
                .addKeyValue("event", event)
                .addKeyValue("reservation_id", t.reservation().reservationId())
                .addKeyValue("show_id", t.reservation().showId())
                .addKeyValue("user_id", t.reservation().userId())
                .addKeyValue("seats_moved", t.seatsMoved())
                .log("{} {}", event, t.reservation().reservationId());
    }

    private static ApiException holdExpired() {
        return ApiException.conflict("hold_expired", "The hold expired before it was confirmed");
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Runs one transaction, retrying when InnoDB picks it as a deadlock victim
     * or a lock wait times out. These shouldn't happen given the lock order,
     * but if they do the client still gets a real answer, not a 500.
     */
    <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (PessimisticLockingFailureException e) {
                metrics.lockRetry();
                log.warn("transaction retry {} after lock failure: {}", attempt, e.getMostSpecificCause().getMessage());
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "try_again",
                            "The seat is under heavy contention, please retry", null);
                }
                sleepQuietly(ThreadLocalRandom.current().nextLong(5, 25) * attempt);
            }
        }
    }

    private Declined seatTaken(String seat) {
        return declined(DeclineReason.SEAT_TAKEN, "Seat " + seat + " is no longer available",
                Map.of("seats", List.of(seat)));
    }

    private Declined declined(DeclineReason reason, String message, Map<String, Object> details) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", reason.code());
        body.put("message", message);
        body.putAll(details);
        return new Declined(reason, json.writeValueAsString(body));
    }

    private static List<String> validateSeats(Show show, List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw ApiException.badRequest("invalid_seats", "seats must be a non-empty array of seat labels");
        }
        if (seats.size() > MAX_SEATS_PER_REQUEST) {
            throw ApiException.badRequest("invalid_seats",
                    "at most " + MAX_SEATS_PER_REQUEST + " seats per request");
        }
        var seen = new HashSet<String>();
        var unknown = new ArrayList<String>();
        for (String seat : seats) {
            if (seat == null) {
                throw ApiException.badRequest("invalid_seats", "seat labels must be strings");
            }
            if (!seen.add(seat)) {
                throw ApiException.badRequest("duplicate_seats", "a seat can only be requested once",
                        Map.of("seat", seat));
            }
            if (!show.seatLabels().contains(seat)) {
                unknown.add(seat);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("unknown_seats", "these seats don't exist in this show",
                    Map.of("seats", unknown));
        }
        return List.copyOf(seats);
    }

    private static String validateKey(String key) {
        if (key == null) {
            return null;
        }
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("invalid_idempotency_key",
                    "idempotency key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    /** Same show + same set of seats = same request, regardless of seat order. */
    static String fingerprint(String showId, List<String> seats) {
        String canonical = showId + "\n" + String.join(",", seats.stream().sorted().toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void requireOwner(ReservationRepository.ReservationRow row, String userId) {
        if (!row.userId().equals(userId)) {
            throw ApiException.forbidden("not_owner", "Only the user who made this reservation can do that");
        }
    }

    static ApiException reservationNotFound() {
        return ApiException.notFound("reservation_not_found", "No such reservation");
    }

    static ReservationView toView(ReservationRepository.ReservationRow row) {
        return new ReservationView(row.id(), row.showId(), row.userId(), row.seats(), row.amountPaise(),
                row.status(), row.expiresAt() == null ? null : row.expiresAt().toInstant(ZoneOffset.UTC));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

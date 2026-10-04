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
import dev.amogh.seats.show.Section;
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

    /** Books specific seats by label. */
    public ReserveResult reserve(String userId, String showId, List<String> requestedSeats, String idempotencyKey) {
        return reserve(userId, showId, new SeatRequest.Named(requestedSeats), idempotencyKey);
    }

    public ReserveResult reserve(String userId, String showId, SeatRequest request, String idempotencyKey) {
        Show show = catalog.require(showId);
        SeatRequest ask = validate(show, request);
        String key = validateKey(idempotencyKey);
        String fingerprint = fingerprint(show.id(), ask);

        Timer.Sample timer = metrics.startTimer();
        long start = System.nanoTime();
        ReserveResult result = withRetry(() ->
                tx.execute(status -> reserveOnce(status, userId, show, ask, key, fingerprint)));
        record(result, userId, show, ask, timer, start);
        return result;
    }

    /** Runs after commit: counters and the log line only ever describe what the database kept. */
    private void record(ReserveResult result, String userId, Show show, SeatRequest ask,
                        Timer.Sample timer, long start) {
        String seats = switch (ask) {
            case SeatRequest.Named named -> String.join(",", named.seats());
            case SeatRequest.Standing standing -> standing.section() + " x" + standing.quantity();
        };
        String outcome;
        String reason = null;
        String reservationId = null;
        switch (result) {
            case Reserved r -> {
                outcome = r.reservation().status();
                reservationId = r.reservation().reservationId();
                seats = String.join(",", r.reservation().seats());
                metrics.reserved(r.reservation().status(), r.reservation().seats().size());
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
                .addKeyValue("seats", seats)
                .addKeyValue("reservation_id", reservationId)
                .addKeyValue("http_status", result.httpStatus())
                .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                .log("reserve {} {}", outcome, reason == null ? "" : reason);
    }

    private ReserveResult reserveOnce(TransactionStatus status, String userId, Show show, SeatRequest ask,
                                      String key, String fingerprint) {
        if (key != null && !idempotency.tryClaim(userId, key, fingerprint)) {
            return replayOrReuse(userId, key, fingerprint);
        }

        Object savepoint = status.createSavepoint();
        ReserveResult result = claim(userId, show, ask);
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

    private ReserveResult replayOrReuse(String userId, String key, String fingerprint) {
        var stored = idempotency.find(userId, key)
                .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
        if (!stored.requestHash().equals(fingerprint)) {
            return declined(DeclineReason.IDEMPOTENCY_KEY_REUSE,
                    "This idempotency key was already used for a different request", Map.of());
        }
        return new Replayed(stored.httpStatus(), stored.response());
    }

    private ReserveResult claim(String userId, Show show, SeatRequest ask) {
        String seatStatus = show.usesHolds() ? "held" : "confirmed";
        LocalDateTime heldUntil = show.usesHolds() ? reservations.dbNowPlusSeconds(show.holdTtlSeconds()) : null;
        String reservationId = UUID.randomUUID().toString();

        Claim claim = claimSeats(userId, show, ask, reservationId, seatStatus, heldUntil);
        if (claim.declined() != null) {
            return claim.declined();
        }
        List<String> seats = claim.seats();
        long amount = priceOf(show, seats);
        reservations.insertReservation(reservationId, show.id(), userId, seats, amount, seatStatus, heldUntil);
        var view = new ReservationView(reservationId, show.id(), userId, seats, amount, seatStatus,
                heldUntil == null ? null : heldUntil.toInstant(ZoneOffset.UTC), shortfall(ask, claim));
        return new Reserved(view, json.writeValueAsString(view));
    }

    /** Seats this step claimed (and, for a partial request, the named ones it couldn't get), or a decline. */
    private record Claim(List<String> seats, List<String> missing, Declined declined) {
        static Claim no(Declined declined) {
            return new Claim(List.of(), List.of(), declined);
        }
    }

    /** Each seat costs its section's price (a flat show has one section). */
    private static long priceOf(Show show, List<String> seats) {
        long amount = 0;
        for (String seat : seats) {
            amount = Math.addExact(amount, show.sectionOf(seat).pricePaise());
        }
        return amount;
    }

    private static ReservationView.Shortfall shortfall(SeatRequest ask, Claim claim) {
        return claim.seats().size() == ask.count() ? null
                : new ReservationView.Shortfall(ask.count(), ask instanceof SeatRequest.Named ? List.copyOf(claim.missing()) : null);
    }

    /**
     * The seat-claiming steps shared by reserve and add-to-hold: fast-path
     * decline, the per-user mutex and limit, then the conditional UPDATEs (named
     * seats in sorted order; standing places via SKIP LOCKED). Claimed seats get
     * {@code reservationId}, {@code seatStatus} and {@code heldUntil}. On a decline
     * the caller rolls back to its savepoint, undoing any seats claimed so far.
     */
    private Claim claimSeats(String userId, Show show, SeatRequest ask, String reservationId,
                             String seatStatus, LocalDateTime heldUntil) {
        // Fast path (plain reads, no locks): drop seats that are visibly taken already.
        // All-or-nothing declines on the first one; a partial request just tries the rest.
        var missing = new ArrayList<String>();
        List<String> candidates = List.of();
        if (ask instanceof SeatRequest.Named named) {
            if (named.allowPartial()) {
                var taken = new HashSet<>(reservations.allUnavailable(show.id(), named.seats()));
                candidates = named.seats().stream().filter(s -> !taken.contains(s)).toList();
                named.seats().stream().filter(taken::contains).forEach(missing::add);
                if (candidates.isEmpty()) {
                    return Claim.no(seatTaken(missing.stream().sorted().findFirst().orElseThrow()));
                }
            } else {
                var visiblyTaken = reservations.firstUnavailable(show.id(), named.seats());
                if (visiblyTaken.isPresent()) {
                    return Claim.no(seatTaken(visiblyTaken.get()));
                }
                candidates = named.seats();
            }
        }
        int wanted = ask instanceof SeatRequest.Named ? candidates.size() : ask.count();

        reservations.lockUser(show.id(), userId);
        int alreadyHeld = reservations.countLiveSeats(show.id(), userId);
        if (alreadyHeld + wanted > show.perUserLimit()) {
            var details = new LinkedHashMap<String, Object>();
            details.put("per_user_limit", show.perUserLimit());
            details.put("seats_held", alreadyHeld);
            details.put("seats_requested", wanted);
            return Claim.no(declined(DeclineReason.PER_USER_LIMIT,
                    "This would exceed the limit of " + show.perUserLimit() + " seats per user", details));
        }

        List<String> seats;
        switch (ask) {
            case SeatRequest.Named named -> {
                // Sorted order is the deadlock-avoidance rule: two requests for {A12, A13}
                // and {A13, A12} both lock A12 first, so neither can hold what the other needs.
                var lost = new HashSet<String>();
                for (String seat : candidates.stream().sorted().toList()) {
                    if (!reservations.claimSeat(show.id(), seat, reservationId, userId, seatStatus, heldUntil)) {
                        if (!named.allowPartial()) {
                            return Claim.no(seatTaken(seat));
                        }
                        lost.add(seat);
                    }
                }
                if (lost.size() == candidates.size()) {
                    return Claim.no(seatTaken(lost.stream().sorted().findFirst().orElseThrow()));
                }
                seats = candidates.stream().filter(s -> !lost.contains(s)).toList();
                named.seats().stream().filter(lost::contains).forEach(missing::add);
            }
            case SeatRequest.Standing standing -> {
                // Any free places will do: lock some (skipping ones others hold), then claim them.
                seats = reservations.lockFreeStandingPlaces(show.id(), standing.section(), standing.quantity());
                if (seats.isEmpty() || (seats.size() < standing.quantity() && !standing.allowPartial())) {
                    return Claim.no(sectionSoldOut(show.section(standing.section()).orElseThrow(), standing, seats.size()));
                }
                for (String seat : seats) {
                    if (!reservations.claimSeat(show.id(), seat, reservationId, userId, seatStatus, heldUntil)) {
                        throw new IllegalStateException("locked standing place " + seat + " was not claimable");
                    }
                }
            }
        }

        return new Claim(seats, missing, null);
    }

    /**
     * POST /reservations/{id}/seats: more seats into a hold you already have, on
     * the SAME timer. You picked two seats, went to pay, came back for a third:
     * all three are paid for together, before the original hold runs out.
     *
     * Lock order: idempotency row, then this reservation's row, then the user
     * mutex, then seats. Reserve never locks a reservation row and cancel/confirm/
     * the sweeper never take the user mutex, so this adds no cycle.
     *
     * Retrying is safe: named seats already in the hold are skipped (re-adding
     * them is a no-op), and an Idempotency-Key covers standing quantities too.
     */
    public ReserveResult addToHold(String userId, String reservationId, SeatRequest request, String idempotencyKey) {
        var existing = reservations.find(reservationId).orElseThrow(ReservationService::reservationNotFound);
        requireOwner(existing, userId);
        Show show = catalog.require(existing.showId());
        SeatRequest ask = validate(show, request);
        String key = validateKey(idempotencyKey);
        String fingerprint = sha256("add-to-hold\n" + reservationId + "\n" + fingerprint(show.id(), ask));

        ReserveResult result = withRetry(() -> tx.execute(status -> addOnce(status, userId, reservationId, show, ask,
                key, fingerprint)));
        switch (result) {
            case Reserved r -> {
                int added = r.reservation().seats().size() - existing.seats().size();
                if (added > 0) {
                    metrics.addedToHold(added);
                }
                log.atInfo().addKeyValue("event", "add_to_hold").addKeyValue("reservation_id", reservationId)
                        .addKeyValue("show_id", show.id()).addKeyValue("user_id", userId)
                        .addKeyValue("seats", String.join(",", r.reservation().seats()))
                        .log("add to hold {}", reservationId);
            }
            case Declined d -> metrics.declined(d.reason());
            case Replayed p -> metrics.declined(DeclineReason.IDEMPOTENT_REPLAY);
        }
        return result;
    }

    private ReserveResult addOnce(TransactionStatus status, String userId, String reservationId, Show show,
                                  SeatRequest ask, String key, String fingerprint) {
        if (key != null && !idempotency.tryClaim(userId, key, fingerprint)) {
            return replayOrReuse(userId, key, fingerprint);
        }
        var row = reservations.findForUpdate(reservationId).orElseThrow(ReservationService::reservationNotFound);
        requireOwner(row, userId);
        switch (row.status()) {
            case "held" -> {
                if (row.holdExpired()) {
                    throw holdExpired();
                }
            }
            case "confirmed" -> throw ApiException.conflict("not_a_hold",
                    "This booking is already paid for; book the new seats separately");
            case "cancelled" -> throw ApiException.conflict("reservation_cancelled", "This reservation was cancelled");
            case "expired" -> throw holdExpired();
            default -> throw new IllegalStateException("unknown reservation status " + row.status());
        }

        // Seats already in this hold are skipped, so re-sending the same add is a no-op.
        SeatRequest remaining = ask instanceof SeatRequest.Named named
                ? new SeatRequest.Named(named.seats().stream().filter(s -> !row.seats().contains(s)).toList(),
                        named.allowPartial())
                : ask;
        ReserveResult result;
        if (remaining.count() == 0) {
            result = holdView(row, row.seats(), row.amountPaise(), null);
        } else {
            Object savepoint = status.createSavepoint();
            Claim claim = claimSeats(userId, show, remaining, row.id(), "held", row.expiresAt());
            if (claim.declined() != null) {
                status.rollbackToSavepoint(savepoint);
                result = claim.declined();
            } else {
                status.releaseSavepoint(savepoint);
                var seats = new ArrayList<>(row.seats());
                seats.addAll(claim.seats());
                long amount = Math.addExact(row.amountPaise(), priceOf(show, claim.seats()));
                reservations.updateSeats(row.id(), seats, amount);
                result = holdView(row, seats, amount, shortfall(remaining, claim));
            }
        }
        if (key != null) {
            idempotency.complete(userId, key, result.httpStatus(), result.body());
        }
        return result;
    }

    private Reserved holdView(ReservationRepository.ReservationRow row, List<String> seats, long amount,
                              ReservationView.Shortfall shortfall) {
        var view = new ReservationView(row.id(), row.showId(), row.userId(), List.copyOf(seats), amount, "held",
                row.expiresAt().toInstant(ZoneOffset.UTC), shortfall);
        return new Reserved(view, json.writeValueAsString(view));
    }

    public ReservationView get(String userId, String reservationId) {
        var row = reservations.find(reservationId).orElseThrow(ReservationService::reservationNotFound);
        requireOwner(row, userId);
        return toView(row);
    }

    /** GET /me/reservations: the caller's own reservations, newest first. */
    public List<ReservationView> listMine(String userId, String showId) {
        return reservations.findByUser(userId, showId, 50).stream().map(ReservationService::toView).toList();
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

    private Declined sectionSoldOut(Section section, SeatRequest.Standing standing, int free) {
        var details = new LinkedHashMap<String, Object>();
        details.put("section", section.code());
        details.put("available", free);
        details.put("requested", standing.quantity());
        return declined(DeclineReason.SECTION_SOLD_OUT,
                free == 0 ? section.name() + " is sold out" : "Only " + free + " places left in " + section.name(),
                details);
    }

    private Declined declined(DeclineReason reason, String message, Map<String, Object> details) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", reason.code());
        body.put("message", message);
        body.putAll(details);
        return new Declined(reason, json.writeValueAsString(body));
    }

    private static SeatRequest validate(Show show, SeatRequest request) {
        return switch (request) {
            case SeatRequest.Named named -> new SeatRequest.Named(validateSeats(show, named.seats()), named.allowPartial());
            case SeatRequest.Standing standing -> {
                if (show.section(standing.section()).filter(Section::standing).isEmpty()) {
                    throw ApiException.badRequest("unknown_section", "this show has no standing section by that code",
                            Map.of("section", standing.section()));
                }
                if (standing.quantity() < 1 || standing.quantity() > MAX_SEATS_PER_REQUEST) {
                    throw ApiException.badRequest("invalid_quantity",
                            "quantity must be between 1 and " + MAX_SEATS_PER_REQUEST);
                }
                yield standing;
            }
        };
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
        for (String seat : seats) {
            Section section = show.sectionOf(seat);
            if (section.standing()) {
                throw ApiException.badRequest("standing_section",
                        "places in a standing section are booked by quantity, not by label",
                        Map.of("section", section.code()));
            }
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
        return fingerprint(showId, new SeatRequest.Named(seats));
    }

    /** Labels can't contain ':', so a standing request can never collide with a named one. */
    static String fingerprint(String showId, SeatRequest ask) {
        String what = switch (ask) {
            case SeatRequest.Named named -> String.join(",", named.seats().stream().sorted().toList());
            case SeatRequest.Standing standing -> "standing:" + standing.section() + ":" + standing.quantity();
        };
        return sha256(showId + "\n" + what + (ask.allowPartial() ? "\npartial" : ""));
    }

    private static String sha256(String canonical) {
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

    /** A hold past its expiry is reported as expired, whether or not the sweeper has got to it yet. */
    static ReservationView toView(ReservationRepository.ReservationRow row) {
        String status = row.status().equals("held") && row.holdExpired() ? "expired" : row.status();
        return new ReservationView(row.id(), row.showId(), row.userId(), row.seats(), row.amountPaise(),
                status, status.equals("held") ? row.expiresAt().toInstant(ZoneOffset.UTC) : null);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

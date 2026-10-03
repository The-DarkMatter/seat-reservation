package dev.amogh.seats.reservation;

/**
 * Outcome of one reserve call. A sealed interface plus records means the
 * controller's {@code switch} over the outcomes is checked by the compiler:
 * add a new outcome and every switch that doesn't handle it stops compiling.
 *
 * Every outcome carries the exact JSON body that was (or will be) sent, so an
 * idempotent replay returns byte-for-byte what the first request got.
 */
public sealed interface ReserveResult {

    int httpStatus();

    String body();

    /** A new reservation was created (confirmed, or held on a hold-mode show). */
    record Reserved(ReservationView reservation, String body) implements ReserveResult {
        public int httpStatus() {
            return 201;
        }
    }

    /** A clean business decline (409). */
    record Declined(DeclineReason reason, String body) implements ReserveResult {
        public int httpStatus() {
            return 409;
        }
    }

    /** Same key, same request: the stored outcome of the first attempt (201 or 409). */
    record Replayed(int httpStatus, String body) implements ReserveResult {
    }
}

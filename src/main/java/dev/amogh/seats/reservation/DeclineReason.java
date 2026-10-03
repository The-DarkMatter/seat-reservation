package dev.amogh.seats.reservation;

/**
 * Why a reserve didn't create a reservation. These are business outcomes, not
 * errors: all of them are 409s, and they double as the {@code reason} label on
 * {@code reservations_declined_total}.
 */
public enum DeclineReason {
    /** Someone else holds or bought one of the requested seats. */
    SEAT_TAKEN("seat_taken"),
    /** The user would end up holding more than per_user_limit seats for this show. */
    PER_USER_LIMIT("per_user_limit"),
    /** A retry of an earlier request: nothing new happened, the stored outcome was returned. */
    IDEMPOTENT_REPLAY("idempotent_replay"),
    /** The idempotency key was already used for a different request. */
    IDEMPOTENCY_KEY_REUSE("idempotency_key_reuse");

    private final String code;

    DeclineReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

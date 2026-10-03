package dev.amogh.seats.reservation;

import java.time.Instant;
import java.util.List;

/**
 * The reservation as the API returns it. {@code expires_at} only appears for
 * holds, and {@code shortfall} only on a partial booking (null fields are
 * omitted from JSON).
 */
public record ReservationView(
        String reservationId,
        String showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status,
        Instant expiresAt,
        Shortfall shortfall) {

    public ReservationView(String reservationId, String showId, String userId, List<String> seats,
                           long amountPaise, String status, Instant expiresAt) {
        this(reservationId, showId, userId, seats, amountPaise, status, expiresAt, null);
    }

    /**
     * What an allow_partial request asked for but didn't get. {@code unavailable}
     * names the missing seats; it's absent for standing places, which have no names.
     */
    public record Shortfall(int requested, List<String> unavailable) {
    }

    public ReservationView withStatus(String newStatus) {
        return new ReservationView(reservationId, showId, userId, seats, amountPaise, newStatus,
                "held".equals(newStatus) ? expiresAt : null);
    }
}

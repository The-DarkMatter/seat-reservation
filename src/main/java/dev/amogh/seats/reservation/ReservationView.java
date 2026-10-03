package dev.amogh.seats.reservation;

import java.time.Instant;
import java.util.List;

/**
 * The reservation as the API returns it. {@code expires_at} only appears for
 * holds (null fields are omitted from JSON).
 */
public record ReservationView(
        String reservationId,
        String showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status,
        Instant expiresAt) {

    public ReservationView withStatus(String newStatus) {
        return new ReservationView(reservationId, showId, userId, seats, amountPaise, newStatus,
                "held".equals(newStatus) ? expiresAt : null);
    }
}

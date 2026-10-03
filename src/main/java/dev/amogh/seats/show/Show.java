package dev.amogh.seats.show;

import java.util.Set;

/**
 * A show's immutable facts. Seats can't be added or repriced after creation,
 * which is what makes it safe to cache this in memory (see {@link ShowCatalog}).
 */
public record Show(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        Integer holdTtlSeconds,
        int totalSeats,
        Set<String> seatLabels) {

    /** Reservations on this show start as time-boxed holds rather than sales. */
    public boolean usesHolds() {
        return holdTtlSeconds != null;
    }
}

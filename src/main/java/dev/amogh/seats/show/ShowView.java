package dev.amogh.seats.show;

import java.util.List;

/** Response body for POST /shows and GET /shows/{id}. */
public record ShowView(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        Integer holdTtlSeconds,
        int totalSeats,
        Counts counts,
        List<SeatView> seats) {

    public record Counts(int available, int held, int confirmed) {
    }

    public record SeatView(String seat, String status) {
    }
}

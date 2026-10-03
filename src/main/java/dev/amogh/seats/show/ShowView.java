package dev.amogh.seats.show;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Response body for POST /shows and GET /shows/{id}. */
public record ShowView(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        Integer holdTtlSeconds,
        int totalSeats,
        String venue,
        Instant startsAt,
        Counts counts,
        List<SectionView> sections,
        JsonNode layout,
        List<SeatView> seats) {

    public record Counts(int available, int held, int confirmed) {

        public static Counts of(int[] c) {
            return new Counts(c[0], c[1], c[2]);
        }
    }

    public record SectionView(
            String code,
            String name,
            long pricePaise,
            boolean standing,
            int capacity,
            Counts counts,
            JsonNode rows,
            JsonNode display) {
    }

    public record SeatView(String seat, String status) {
    }
}

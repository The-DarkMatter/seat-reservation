package dev.amogh.seats.show;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** One entry of GET /shows: what an event listing needs, without the per-seat detail. */
public record ShowSummary(
        String id,
        String name,
        String venue,
        Instant startsAt,
        long pricePaise,
        Integer holdTtlSeconds,
        int totalSeats,
        ShowView.Counts counts,
        List<ShowView.SectionView> sections,
        JsonNode layout) {
}

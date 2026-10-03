package dev.amogh.seats.show;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Body of POST /shows. Boxed types so a missing field is distinguishable from 0.
 * price_paise is a Long: a JSON float like 250.5 is rejected at parse time
 * (accept-float-as-int is off), so money never passes through a double.
 *
 * Two shapes. The original: {@code seats} (labels) + one {@code price_paise}.
 * The sectioned one: {@code sections}, each with its own price, laid out as
 * rows of seats or as a standing area with a capacity. Send one or the other.
 */
public record CreateShowRequest(
        String name,
        List<String> seats,
        Long pricePaise,
        Integer perUserLimit,
        Integer holdTtlSeconds,
        String venue,
        Instant startsAt,
        List<SectionSpec> sections,
        JsonNode layout) {

    public CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit,
                             Integer holdTtlSeconds) {
        this(name, seats, pricePaise, perUserLimit, holdTtlSeconds, null, null, null, null);
    }

    /** Seated: give {@code rows}. Standing: {@code standing: true} and a {@code capacity}. */
    public record SectionSpec(
            String code,
            String name,
            Long pricePaise,
            Boolean standing,
            Integer capacity,
            List<RowSpec> rows,
            JsonNode display) {
    }

    /** Seats are numbered 1..seats. {@code aisles_after} only tells the UI where to draw gaps. */
    public record RowSpec(String row, Integer seats, List<Integer> aislesAfter) {
    }
}

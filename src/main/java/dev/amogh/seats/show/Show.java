package dev.amogh.seats.show;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * A show's immutable facts. Seats can't be added or repriced after creation,
 * which is what makes it safe to cache this in memory (see {@link ShowCatalog}).
 *
 * @param pricePaise   the lowest section price ("from ₹…"); a flat show's only price
 * @param seatSections every seat label mapped to its section code, in creation order
 */
public record Show(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        Integer holdTtlSeconds,
        int totalSeats,
        String venue,
        Instant startsAt,
        String kind,
        JsonNode layout,
        List<Section> sections,
        Map<String, String> seatSections) {

    /** Reservations on this show start as time-boxed holds rather than sales. */
    public boolean usesHolds() {
        return holdTtlSeconds != null;
    }

    public Set<String> seatLabels() {
        return seatSections.keySet();
    }

    public Optional<Section> section(String code) {
        return sections.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    /** The section a seat belongs to. Only call with a label of this show. */
    public Section sectionOf(String label) {
        return section(seatSections.get(label)).orElseThrow();
    }
}

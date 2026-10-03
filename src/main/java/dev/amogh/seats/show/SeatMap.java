package dev.amogh.seats.show;

import java.util.List;

/**
 * GET /shows/{id}/seatmap: the live state of every seat, compact enough for a
 * browser to poll every second. Each seated section's {@code states} has one
 * character per seat in layout order (rows in order, seats 1..n):
 * {@code a} available, {@code h} held, {@code c} confirmed. Standing sections
 * only report counts, since nobody picks those places by name.
 */
public record SeatMap(String showId, ShowView.Counts counts, List<SectionState> sections) {

    public record SectionState(String code, ShowView.Counts counts, String states) {
    }
}

package dev.amogh.seats.reservation;

import java.util.List;

/**
 * What a reserve asks for: specific seats by label, or a number of places in a
 * standing section where any free place will do.
 *
 * {@code allowPartial} picks what happens when only some of it is available:
 * false (the default) books nothing and declines; true books whatever could
 * be had and says what was missing.
 */
public sealed interface SeatRequest {

    int count();

    boolean allowPartial();

    record Named(List<String> seats, boolean allowPartial) implements SeatRequest {
        public Named(List<String> seats) {
            this(seats, false);
        }

        public int count() {
            return seats.size();
        }
    }

    record Standing(String section, int quantity, boolean allowPartial) implements SeatRequest {
        public Standing(String section, int quantity) {
            this(section, quantity, false);
        }

        public int count() {
            return quantity;
        }
    }
}

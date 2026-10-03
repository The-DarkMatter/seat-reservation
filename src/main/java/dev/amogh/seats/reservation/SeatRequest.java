package dev.amogh.seats.reservation;

import java.util.List;

/**
 * What a reserve asks for: specific seats by label, or a number of places in a
 * standing section where any free place will do.
 */
public sealed interface SeatRequest {

    int count();

    record Named(List<String> seats) implements SeatRequest {
        public int count() {
            return seats.size();
        }
    }

    record Standing(String section, int quantity) implements SeatRequest {
        public int count() {
            return quantity;
        }
    }
}

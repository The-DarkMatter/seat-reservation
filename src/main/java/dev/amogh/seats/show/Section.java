package dev.amogh.seats.show;

import tools.jackson.databind.JsonNode;

/**
 * A price class within a show. Seated sections are rows of named seats;
 * standing sections are a pool of interchangeable places, booked by quantity.
 * {@code rows} and {@code display} are layout for the UI, stored as given.
 */
public record Section(
        String code,
        String name,
        long pricePaise,
        boolean standing,
        int sort,
        int capacity,
        JsonNode rows,
        JsonNode display) {

    /** What a show created from a flat seat list gets: one section, one price. */
    public static final String GENERAL = "GENERAL";
}

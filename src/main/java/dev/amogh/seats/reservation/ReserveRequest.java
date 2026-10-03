package dev.amogh.seats.reservation;

import java.util.List;

/**
 * Body of POST /shows/{id}/reserve. There is deliberately no user field:
 * identity comes from the bearer token. A {@code user_id} in the JSON is
 * an unknown property, and unknown properties are ignored.
 *
 * Either {@code seats} (labels), or {@code section} + {@code quantity} for a
 * standing section, where any free places will do. {@code allow_partial: true}
 * books whatever is available instead of all-or-nothing.
 */
public record ReserveRequest(List<String> seats, String idempotencyKey, String section, Integer quantity,
                             Boolean allowPartial) {
}

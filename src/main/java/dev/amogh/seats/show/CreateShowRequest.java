package dev.amogh.seats.show;

import java.util.List;

/**
 * Body of POST /shows. Boxed types so a missing field is distinguishable from 0.
 * price_paise is a Long: a JSON float like 250.5 is rejected at parse time
 * (accept-float-as-int is off), so money never passes through a double.
 */
public record CreateShowRequest(
        String name,
        List<String> seats,
        Long pricePaise,
        Integer perUserLimit,
        Integer holdTtlSeconds) {
}

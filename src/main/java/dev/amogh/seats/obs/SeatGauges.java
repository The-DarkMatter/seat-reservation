package dev.amogh.seats.obs;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * seats_available / seats_held / seats_confirmed / seats_capacity per show, for
 * the most recent shows.
 *
 * These are read FROM MYSQL (one statement per refresh, so the four numbers
 * share a snapshot), not tracked in memory. That's what makes them reconcile
 * with GET /shows/{id} and survive restarts. The alternative, a per-show
 * counter row updated by every reservation, would turn that row into a hot
 * spot that serializes the whole on-sale burst.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private static final int RECENT_SHOWS = 20;

    private final JdbcClient jdbc;
    private final MultiGauge available;
    private final MultiGauge held;
    private final MultiGauge confirmed;
    private final MultiGauge total;

    record ShowCounts(String id, String name, int total, int available, int held, int confirmed) {
    }

    public SeatGauges(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        available = MultiGauge.builder("seats.available").description("Seats free to book").register(registry);
        held = MultiGauge.builder("seats.held").description("Seats on a live hold").register(registry);
        confirmed = MultiGauge.builder("seats.confirmed").description("Seats sold").register(registry);
        total = MultiGauge.builder("seats.capacity").description("Seats in the show (available + held + confirmed must equal this)").register(registry);
    }

    @Scheduled(fixedDelayString = "${app.gauge-refresh:1s}")
    public void refresh() {
        try {
            var rows = jdbc.sql("""
                    SELECT s.id, s.name, s.total_seats,
                           SUM(se.status = 'available' OR (se.status = 'held' AND se.held_until <= NOW(6))) AS available,
                           SUM(se.status = 'held' AND se.held_until > NOW(6)) AS held,
                           SUM(se.status = 'confirmed') AS confirmed
                    FROM (SELECT id, name, total_seats FROM shows ORDER BY created_at DESC LIMIT ?) s
                    JOIN seats se ON se.show_id = s.id
                    GROUP BY s.id, s.name, s.total_seats
                    """)
                    .param(RECENT_SHOWS)
                    .query((rs, n) -> new ShowCounts(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6)))
                    .list();
            var a = new ArrayList<MultiGauge.Row<?>>();
            var h = new ArrayList<MultiGauge.Row<?>>();
            var c = new ArrayList<MultiGauge.Row<?>>();
            var t = new ArrayList<MultiGauge.Row<?>>();
            for (var row : rows) {
                var tags = Tags.of("show_id", row.id(), "show", row.name());
                a.add(MultiGauge.Row.of(tags, row.available()));
                h.add(MultiGauge.Row.of(tags, row.held()));
                c.add(MultiGauge.Row.of(tags, row.confirmed()));
                t.add(MultiGauge.Row.of(tags, row.total()));
            }
            // overwrite=true drops shows that fell out of the recent window.
            available.register(List.copyOf(a), true);
            held.register(List.copyOf(h), true);
            confirmed.register(List.copyOf(c), true);
            total.register(List.copyOf(t), true);
        } catch (RuntimeException e) {
            // Keep the last values; readiness reports the DB problem.
            log.warn("seat gauge refresh failed: {}", e.getMessage());
        }
    }
}

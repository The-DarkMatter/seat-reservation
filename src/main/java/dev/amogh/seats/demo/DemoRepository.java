package dev.amogh.seats.demo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DemoRepository {

    private final JdbcClient jdbc;

    public DemoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest visible featured show with this name, with how sold it is and how old. */
    public record FeaturedState(String id, int totalSeats, int taken, long ageSeconds) {
    }

    public Optional<FeaturedState> latestFeatured(String name) {
        return jdbc.sql("""
                SELECT s.id, s.total_seats,
                       (SELECT COUNT(*) FROM seats se WHERE se.show_id = s.id
                          AND (se.status = 'confirmed' OR (se.status = 'held' AND se.held_until > NOW(6)))) AS taken,
                       TIMESTAMPDIFF(SECOND, s.created_at, NOW(6)) AS age
                FROM shows s
                WHERE s.kind = 'featured' AND s.hidden = FALSE AND s.name = ?
                ORDER BY s.created_at DESC LIMIT 1
                """)
                .param(name)
                .query((rs, n) -> new FeaturedState(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)))
                .optional();
    }

    /** Hides every featured show with this name except {@code keepId} (it stays reachable by link). */
    public int hideOtherFeatured(String name, String keepId) {
        return jdbc.sql("UPDATE shows SET hidden = TRUE WHERE kind = 'featured' AND name = ? AND id <> ? AND hidden = FALSE")
                .params(name, keepId)
                .update();
    }

    public int countDemoShows() {
        return jdbc.sql("SELECT COUNT(*) FROM shows WHERE kind = 'demo'").query(Integer.class).single();
    }

    /** Visitors' demo shows and retired featured shows older than the retention period. */
    public List<String> expiredShows(long retentionSeconds, int limit) {
        return jdbc.sql("""
                SELECT id FROM shows
                WHERE (kind = 'demo' OR (kind = 'featured' AND hidden = TRUE))
                  AND created_at < NOW(6) - INTERVAL ? SECOND
                ORDER BY created_at LIMIT ?
                """)
                .params(retentionSeconds, limit)
                .query(String.class)
                .list();
    }

    /** Children first, so every foreign key is satisfied at each step. Run in one transaction. */
    public void deleteShow(String showId) {
        for (String table : List.of("user_show_locks", "reservations", "seats", "sections")) {
            jdbc.sql("DELETE FROM " + table + " WHERE show_id = ?").param(showId).update();
        }
        jdbc.sql("DELETE FROM shows WHERE id = ?").param(showId).update();
    }

    /** Idempotency keys are honoured for the retention period, like a payment API's. */
    public int deleteOldIdempotencyKeys(long retentionSeconds, int batch) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < NOW(6) - INTERVAL ? SECOND LIMIT ?")
                .params(retentionSeconds, batch)
                .update();
    }
}

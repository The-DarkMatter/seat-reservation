package dev.amogh.seats.show;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    private static final int INSERT_BATCH = 1000;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ShowRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertShow(Show show) {
        jdbc.sql("""
                INSERT INTO shows (id, name, price_paise, per_user_limit, hold_ttl_seconds, total_seats)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                        show.holdTtlSeconds(), show.totalSeats())
                .update();
    }

    /** Batched; rewriteBatchedStatements turns each chunk into one multi-row INSERT. */
    public void insertSeats(String showId, List<String> labels) {
        var rows = new ArrayList<Object[]>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            rows.add(new Object[] {showId, labels.get(i), i});
        }
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            var chunk = rows.subList(from, Math.min(from + INSERT_BATCH, rows.size()));
            jdbcTemplate.batchUpdate("INSERT INTO seats (show_id, label, idx) VALUES (?, ?, ?)", chunk);
        }
    }

    public Optional<Show> findShow(String id) {
        Optional<Show> header = jdbc.sql("""
                SELECT id, name, price_paise, per_user_limit, hold_ttl_seconds, total_seats
                FROM shows WHERE id = ?
                """)
                .param(id)
                .query((rs, n) -> new Show(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getObject("hold_ttl_seconds", Integer.class),
                        rs.getInt("total_seats"),
                        Set.of()))
                .optional();
        return header.map(s -> new Show(s.id(), s.name(), s.pricePaise(), s.perUserLimit(),
                s.holdTtlSeconds(), s.totalSeats(), new LinkedHashSet<>(findLabels(id))));
    }

    private List<String> findLabels(String showId) {
        return jdbc.sql("SELECT label FROM seats WHERE show_id = ? ORDER BY idx")
                .param(showId)
                .query(String.class)
                .list();
    }

    /**
     * Every seat's status in creation order, read in ONE statement so the list
     * and the counts derived from it come from the same snapshot. A hold past
     * its expiry is reported as available: that is what it is, whether or not
     * the sweeper has tidied the row yet.
     */
    public List<ShowView.SeatView> findSeatStates(String showId) {
        return jdbc.sql("""
                SELECT label,
                       CASE WHEN status = 'held' AND held_until <= NOW(6) THEN 'available'
                            ELSE status END AS effective_status
                FROM seats WHERE show_id = ? ORDER BY idx
                """)
                .param(showId)
                .query((rs, n) -> new ShowView.SeatView(rs.getString(1), rs.getString(2)))
                .list();
    }
}

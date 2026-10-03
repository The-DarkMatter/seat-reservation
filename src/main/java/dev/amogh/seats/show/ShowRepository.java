package dev.amogh.seats.show;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ShowRepository {

    private static final int INSERT_BATCH = 1000;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper json;

    public ShowRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper json) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
    }

    /** One seat to create: its label and the section it belongs to. */
    public record SeatRow(String label, String section) {
    }

    public void insertShow(Show show) {
        jdbc.sql("""
                INSERT INTO shows (id, name, price_paise, per_user_limit, hold_ttl_seconds, total_seats,
                                   venue, starts_at, kind, layout)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                        show.holdTtlSeconds(), show.totalSeats(), show.venue(), toUtc(show.startsAt()),
                        show.kind(), toJson(show.layout()))
                .update();
    }

    public void insertSections(String showId, List<Section> sections) {
        var rows = sections.stream()
                .map(s -> new Object[] {showId, s.code(), s.name(), s.pricePaise(), s.standing(), s.sort(),
                        s.capacity(), toJson(s.rows()), toJson(s.display())})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO sections (show_id, code, name, price_paise, standing, sort, capacity, rows_json, display)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    /** Batched; rewriteBatchedStatements turns each chunk into one multi-row INSERT. */
    public void insertSeats(String showId, List<SeatRow> seats) {
        var rows = new ArrayList<Object[]>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            rows.add(new Object[] {showId, seats.get(i).label(), i, seats.get(i).section()});
        }
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            var chunk = rows.subList(from, Math.min(from + INSERT_BATCH, rows.size()));
            jdbcTemplate.batchUpdate("INSERT INTO seats (show_id, label, idx, section) VALUES (?, ?, ?, ?)", chunk);
        }
    }

    public Optional<Show> findShow(String id) {
        record Header(String id, String name, long price, int limit, Integer ttl, int total, String venue,
                      Instant startsAt, String kind, JsonNode layout) {
        }
        return jdbc.sql("""
                SELECT id, name, price_paise, per_user_limit, hold_ttl_seconds, total_seats,
                       venue, starts_at, kind, layout
                FROM shows WHERE id = ?
                """)
                .param(id)
                .query((rs, n) -> new Header(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"),
                        rs.getObject("hold_ttl_seconds", Integer.class),
                        rs.getInt("total_seats"),
                        rs.getString("venue"),
                        fromUtc(rs.getObject("starts_at", LocalDateTime.class)),
                        rs.getString("kind"),
                        readJson(rs, "layout")))
                .optional()
                .map(h -> new Show(h.id(), h.name(), h.price(), h.limit(), h.ttl(), h.total(), h.venue(),
                        h.startsAt(), h.kind(), h.layout(), findSections(id), findSeatSections(id)));
    }

    private List<Section> findSections(String showId) {
        return jdbc.sql("""
                SELECT code, name, price_paise, standing, sort, capacity, rows_json, display
                FROM sections WHERE show_id = ? ORDER BY sort
                """)
                .param(showId)
                .query((rs, n) -> new Section(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("price_paise"),
                        rs.getBoolean("standing"),
                        rs.getInt("sort"),
                        rs.getInt("capacity"),
                        readJson(rs, "rows_json"),
                        readJson(rs, "display")))
                .list();
    }

    private LinkedHashMap<String, String> findSeatSections(String showId) {
        var map = new LinkedHashMap<String, String>();
        jdbc.sql("SELECT label, section FROM seats WHERE show_id = ? ORDER BY idx")
                .param(showId)
                .query(rs -> {
                    map.put(rs.getString(1), rs.getString(2));
                });
        return map;
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

    static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant fromUtc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : json.writeValueAsString(node);
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : json.readTree(raw);
    }
}

package dev.amogh.seats.reservation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * All the SQL that moves seats. Every state change is a single conditional
 * UPDATE whose WHERE clause states what must be true for the change to be
 * legal. InnoDB evaluates that WHERE clause against the latest committed row
 * while holding the row lock, so check-and-write is one atomic step.
 * There is no "read, decide in Java, then write" anywhere in here.
 */
@Repository
public class ReservationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    /** A seat is claimable if nobody owns it, or if its owner's hold has run out. */
    private static final String CLAIMABLE = "(status = 'available' OR (status = 'held' AND held_until <= NOW(6)))";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReservationRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record ReservationRow(
            String id,
            String showId,
            String userId,
            List<String> seats,
            long amountPaise,
            String status,
            LocalDateTime expiresAt,
            boolean holdExpired) {
    }

    // ---- reserve -----------------------------------------------------------

    /**
     * Takes this user's per-show mutex for the rest of the transaction. The
     * upsert makes sure the row exists (first reserve for this user+show); the
     * SELECT ... FOR UPDATE is the lock. A second request from the same user
     * waits here until the first commits, then sees its seats when it counts.
     */
    public void lockUser(String showId, String userId) {
        jdbc.sql("INSERT INTO user_show_locks (show_id, user_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE user_id = user_id")
                .params(showId, userId)
                .update();
        jdbc.sql("SELECT 1 FROM user_show_locks WHERE show_id = ? AND user_id = ? FOR UPDATE")
                .params(showId, userId)
                .query(Integer.class)
                .single();
    }

    /**
     * Seats this user currently holds or owns for the show, counted from the
     * seats themselves, so there is no counter to drift. Run under READ
     * COMMITTED after {@link #lockUser}, it sees every commit up to now. (Under
     * REPEATABLE READ this plain SELECT could read an older snapshot and miss
     * the seats a parallel request just committed.)
     */
    public int countLiveSeats(String showId, String userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM seats
                WHERE show_id = ? AND user_id = ?
                  AND (status = 'confirmed' OR (status = 'held' AND held_until > NOW(6)))
                """)
                .params(showId, userId)
                .query(Integer.class)
                .single();
    }

    /** Expiry is always computed by the database clock, never an app server's. */
    public LocalDateTime dbNowPlusSeconds(int seconds) {
        return jdbc.sql("SELECT NOW(6) + INTERVAL ? SECOND")
                .param(seconds)
                .query(LocalDateTime.class)
                .single();
    }

    /**
     * THE atomic decision. One row, one statement: succeed only if the seat is
     * claimable right now. If another transaction holds the row lock, this
     * waits, then re-checks the WHERE clause against the newly committed row;
     * that's why 500 parallel attempts on one seat produce exactly one
     * success. Returns false if the seat was taken.
     */
    public boolean claimSeat(String showId, String label, String reservationId, String userId,
                             String status, LocalDateTime heldUntil) {
        int updated = jdbc.sql("UPDATE seats SET status = ?, reservation_id = ?, user_id = ?, held_until = ?"
                        + " WHERE show_id = ? AND label = ? AND " + CLAIMABLE)
                .params(status, reservationId, userId, heldUntil, showId, label)
                .update();
        return updated == 1;
    }

    /**
     * Fast-path decline: the first requested seat that is visibly taken right
     * now, read WITHOUT locking (a plain consistent read).
     *
     * Why it exists: under READ COMMITTED, an UPDATE that had to WAIT for a row
     * lock and then finds the row no longer matches does not release that lock;
     * it keeps it until commit. (Without a wait, the lock on a non-matching row
     * is released immediately.) In a hot-seat storm every loser that queued
     * behind the winner therefore held the row for the rest of its transaction,
     * and 500 buyers went through single file. Checking first means requests
     * arriving after the winner commits never join the lock queue at all.
     *
     * This can only ever say "taken". The grant still happens exclusively in
     * {@link #claimSeat}, so a stale read can't double-sell; at worst it
     * declines a seat that was released a moment ago, which is a legitimate
     * outcome at the moment of the read.
     */
    public Optional<String> firstUnavailable(String showId, List<String> labels) {
        return jdbc.sql("SELECT label FROM seats WHERE show_id = :show AND label IN (:labels) AND NOT "
                        + CLAIMABLE + " ORDER BY label LIMIT 1")
                .param("show", showId)
                .param("labels", labels)
                .query(String.class)
                .optional();
    }

    public void insertReservation(String id, String showId, String userId, List<String> seats,
                                  long amountPaise, String status, LocalDateTime expiresAt) {
        jdbc.sql("""
                INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(id, showId, userId, json.writeValueAsString(seats), amountPaise, status, expiresAt)
                .update();
    }

    // ---- cancel / confirm / expire -------------------------------------------

    public Optional<ReservationRow> findForUpdate(String id) {
        return find(id, true);
    }

    public Optional<ReservationRow> find(String id) {
        return find(id, false);
    }

    private Optional<ReservationRow> find(String id, boolean lock) {
        return jdbc.sql("""
                SELECT id, show_id, user_id, seats, amount_paise, status, expires_at,
                       (expires_at IS NOT NULL AND expires_at <= NOW(6)) AS hold_expired
                FROM reservations WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((rs, n) -> new ReservationRow(
                        rs.getString("id"),
                        rs.getString("show_id"),
                        rs.getString("user_id"),
                        json.readValue(rs.getString("seats"), STRING_LIST),
                        rs.getLong("amount_paise"),
                        rs.getString("status"),
                        rs.getObject("expires_at", LocalDateTime.class),
                        rs.getBoolean("hold_expired")))
                .optional();
    }

    /**
     * Frees exactly the seats this reservation still owns. The
     * {@code reservation_id = ?} guard is what makes a release safe: if the
     * hold expired and someone else re-booked the seat, the row now carries
     * their reservation_id and this statement doesn't touch it.
     * Locks follow the (reservation_id, show_id, label) index, i.e. label
     * order, the same order reserve uses, so the two can't deadlock.
     */
    public int releaseSeats(String reservationId) {
        return jdbc.sql("""
                UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL, held_until = NULL
                WHERE reservation_id = ?
                """)
                .param(reservationId)
                .update();
    }

    /** Releases only seats whose hold has actually expired (used by the sweeper). */
    public int releaseExpiredSeats(String reservationId) {
        return jdbc.sql("""
                UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL, held_until = NULL
                WHERE reservation_id = ? AND status = 'held' AND held_until <= NOW(6)
                """)
                .param(reservationId)
                .update();
    }

    /** Turns a still-live hold into a sale. Zero rows means the hold ran out first. */
    public int confirmSeats(String reservationId) {
        return jdbc.sql("""
                UPDATE seats SET status = 'confirmed', held_until = NULL
                WHERE reservation_id = ? AND status = 'held' AND held_until > NOW(6)
                """)
                .param(reservationId)
                .update();
    }

    public void setStatus(String id, String status) {
        jdbc.sql("UPDATE reservations SET status = ?, expires_at = IF(? = 'held', expires_at, NULL) WHERE id = ?")
                .params(status, status, id)
                .update();
    }

    /** Expired holds the sweeper can take right now; rows locked by a confirm/cancel are skipped. */
    public List<String> lockExpiredHolds(int limit) {
        return jdbc.sql("""
                SELECT id FROM reservations
                WHERE status = 'held' AND expires_at <= NOW(6)
                ORDER BY expires_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """)
                .param(limit)
                .query(String.class)
                .list();
    }
}

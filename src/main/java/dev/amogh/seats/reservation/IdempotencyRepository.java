package dev.amogh.seats.reservation;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record StoredOutcome(String requestHash, int httpStatus, String response) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this transaction. Returns false if the key already
     * exists. If another transaction inserted the same key and hasn't committed
     * yet, InnoDB makes this INSERT wait for it; once it commits we get the
     * duplicate-key error and the caller replays the committed outcome.
     *
     * A plain INSERT, not INSERT IGNORE: IGNORE would also swallow every other
     * error (bad data, truncation) and turn it into a silent "already exists".
     */
    public boolean tryClaim(String userId, String key, String requestHash) {
        try {
            jdbc.sql("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)")
                    .params(userId, key, requestHash)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<StoredOutcome> find(String userId, String key) {
        return jdbc.sql("""
                SELECT request_hash, http_status, response
                FROM idempotency_keys WHERE user_id = ? AND idem_key = ?
                """)
                .params(userId, key)
                .query((rs, n) -> new StoredOutcome(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .optional();
    }

    /** Records the outcome in the same transaction that produced it. */
    public void complete(String userId, String key, int httpStatus, String response) {
        jdbc.sql("UPDATE idempotency_keys SET http_status = ?, response = ? WHERE user_id = ? AND idem_key = ?")
                .params(httpStatus, response, userId, key)
                .update();
    }
}

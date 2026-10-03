CREATE TABLE reservations (
    id           CHAR(36)    NOT NULL,
    show_id      CHAR(36)    NOT NULL,
    user_id      VARCHAR(64) NOT NULL,
    seats        JSON        NOT NULL,
    amount_paise BIGINT      NOT NULL,
    status       ENUM ('held', 'confirmed', 'cancelled', 'expired') NOT NULL,
    expires_at   DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_reservations_expiry (status, expires_at),
    KEY idx_reservations_user (user_id, show_id),
    CONSTRAINT fk_reservations_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT chk_reservations_amount CHECK (amount_paise >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- Exactly-once for reserve. The primary key is the guarantee: two requests
-- with the same (user, key) can't both insert a row, and the loser blocks on
-- the winner's row lock until it commits, then replays its stored outcome.
-- Keys are scoped per user, so one user can never replay another's key.
--
-- response is TEXT, not JSON: MySQL's JSON type re-orders object keys on
-- storage, and a replay should return the original body byte-for-byte.
CREATE TABLE idempotency_keys (
    user_id      VARCHAR(64)  NOT NULL,
    idem_key     VARCHAR(128) NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    http_status  SMALLINT     NOT NULL DEFAULT 0,
    response     TEXT         NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, idem_key),
    KEY idx_idempotency_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- One row per (show, user), used only as a mutex: a reserve locks it FOR
-- UPDATE before counting the user's seats, so two parallel requests from the
-- same user are serialized and can't both pass the per-user limit check.
CREATE TABLE user_show_locks (
    show_id CHAR(36)    NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (show_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

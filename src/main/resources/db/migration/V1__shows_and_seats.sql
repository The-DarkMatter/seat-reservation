-- utf8mb4_0900_bin: seat labels and user ids compare byte-for-byte with no
-- trailing-space padding, so the DB agrees exactly with the uniqueness checks
-- done in Java ("A1" vs "a1" vs "A1 " are all different seats).

CREATE TABLE shows (
    id               CHAR(36)     NOT NULL,
    name             VARCHAR(200) NOT NULL,
    price_paise      BIGINT       NOT NULL,
    per_user_limit   INT          NOT NULL,
    hold_ttl_seconds INT          NULL,
    total_seats      INT          NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_shows_created (created_at),
    CONSTRAINT chk_shows_price CHECK (price_paise >= 0),
    CONSTRAINT chk_shows_limit CHECK (per_user_limit > 0),
    CONSTRAINT chk_shows_ttl CHECK (hold_ttl_seconds IS NULL OR hold_ttl_seconds > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

-- One row per seat, and the row IS the seat's state. A seat has exactly one
-- status at a time, so available + held + confirmed == total_seats holds by
-- construction. Every claim/release is a conditional UPDATE on this row.
CREATE TABLE seats (
    show_id        CHAR(36)    NOT NULL,
    label          VARCHAR(32) NOT NULL,
    idx            INT         NOT NULL,
    status         ENUM ('available', 'held', 'confirmed') NOT NULL DEFAULT 'available',
    reservation_id CHAR(36)    NULL,
    user_id        VARCHAR(64) NULL,
    held_until     DATETIME(6) NULL,
    PRIMARY KEY (show_id, label),
    KEY idx_seats_order (show_id, idx),
    KEY idx_seats_user (show_id, user_id),
    KEY idx_seats_reservation (reservation_id),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id),
    -- A seat is owned by a reservation exactly when it is not available...
    CONSTRAINT chk_seats_owner CHECK ((status = 'available') = (reservation_id IS NULL)),
    -- ...and carries an expiry exactly when it is a hold.
    CONSTRAINT chk_seats_hold CHECK ((status = 'held') = (held_until IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

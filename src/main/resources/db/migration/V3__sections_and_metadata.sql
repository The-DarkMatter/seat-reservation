-- Kursi UI: seat classes with their own price, standing (first-come) areas,
-- and the event details a booking page shows. Purely additive: a show created
-- the old way (a flat seat list + one price) becomes a single GENERAL section.

ALTER TABLE shows
    ADD COLUMN venue     VARCHAR(200) NULL,
    ADD COLUMN starts_at DATETIME(6)  NULL,
    -- api: created through POST /shows. featured: the demo events on the home
    -- page. demo: a visitor's own "rush" show, deleted after a day.
    ADD COLUMN kind      ENUM ('api', 'featured', 'demo') NOT NULL DEFAULT 'api',
    ADD COLUMN hidden    BOOLEAN NOT NULL DEFAULT FALSE,
    -- Venue geometry for the UI (stage, canvas size). Never read by the backend.
    ADD COLUMN layout    JSON NULL,
    ADD KEY idx_shows_kind (kind, hidden, created_at);

-- A section is a price class. Seated sections are drawn as rows of seats;
-- standing sections are a pool of interchangeable places ("3 in Silver").
CREATE TABLE sections (
    show_id     CHAR(36)     NOT NULL,
    code        VARCHAR(16)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    price_paise BIGINT       NOT NULL,
    standing    BOOLEAN      NOT NULL DEFAULT FALSE,
    sort        INT          NOT NULL,
    capacity    INT          NOT NULL,
    rows_json   JSON         NULL, -- seated: [{"row":"A","seats":24,"aisles_after":[6,18]}, ...]
    display     JSON         NULL, -- UI only: colour, position on the venue map
    PRIMARY KEY (show_id, code),
    CONSTRAINT fk_sections_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT chk_sections_price CHECK (price_paise >= 0),
    CONSTRAINT chk_sections_capacity CHECK (capacity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

INSERT INTO sections (show_id, code, name, price_paise, standing, sort, capacity)
SELECT id, 'GENERAL', 'General', price_paise, FALSE, 0, total_seats FROM shows;

-- Every seat belongs to exactly one section of its own show. The foreign key
-- is only checked when a seat is inserted (claims never change section).
ALTER TABLE seats
    ADD COLUMN section VARCHAR(16) NOT NULL DEFAULT 'GENERAL',
    ADD KEY idx_seats_section (show_id, section, idx),
    ADD CONSTRAINT fk_seats_section FOREIGN KEY (show_id, section) REFERENCES sections (show_id, code);

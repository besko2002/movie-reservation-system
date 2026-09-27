-- V4: theaters (halls) and their generated seats (phase 4 - theaters + seat generation).
-- Owned by the theaters module; no other module writes to these tables.
CREATE TABLE theaters (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120) NOT NULL UNIQUE,
    location      VARCHAR(200),
    total_rows    INT          NOT NULL CHECK (total_rows BETWEEN 1 AND 26),
    seats_per_row INT          NOT NULL CHECK (seats_per_row BETWEEN 1 AND 50),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    theater_id  BIGINT      NOT NULL REFERENCES theaters(id) ON DELETE CASCADE,
    row_label   VARCHAR(2)  NOT NULL,
    seat_number INT         NOT NULL CHECK (seat_number > 0),
    type        VARCHAR(20) NOT NULL CHECK (type IN ('NORMAL', 'VIP')),
    UNIQUE (theater_id, row_label, seat_number)
);

-- Seat lists are always read per theater (seat map, seat generation, deletion).
CREATE INDEX idx_seats_theater_id ON seats (theater_id);

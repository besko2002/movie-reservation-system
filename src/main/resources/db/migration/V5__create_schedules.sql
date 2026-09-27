-- V5: movie schedules / showtimes (phase 5).
-- Owned by the movies module; theaters are referenced by id only, never by joining their entities.
--
-- end_time is always computed by the application as
--     start_time + movies.duration_minutes + app.schedule.buffer-minutes (cleaning buffer),
-- so the overlap checks below (and in ScheduleService) already cover cleaning time.
CREATE TABLE movie_schedules (
    id         BIGSERIAL PRIMARY KEY,
    movie_id   BIGINT         NOT NULL REFERENCES movies(id)   ON DELETE RESTRICT,
    theater_id BIGINT         NOT NULL REFERENCES theaters(id) ON DELETE RESTRICT,
    start_time TIMESTAMPTZ    NOT NULL,
    end_time   TIMESTAMPTZ    NOT NULL,
    base_price NUMERIC(10, 2) NOT NULL CHECK (base_price >= 0),
    vip_price  NUMERIC(10, 2) NOT NULL CHECK (vip_price >= 0),
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_movie_schedules_window CHECK (end_time > start_time),
    CONSTRAINT ck_movie_schedules_vip_price CHECK (vip_price >= base_price)
);

-- Overlap detection always asks "what runs in this theater around this time".
CREATE INDEX idx_movie_schedules_theater_start ON movie_schedules (theater_id, start_time);

-- Public browsing filters by movie and orders by start time.
CREATE INDEX idx_movie_schedules_movie_start ON movie_schedules (movie_id, start_time);

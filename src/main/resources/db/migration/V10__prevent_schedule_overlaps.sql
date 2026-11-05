-- The application serializes overlap checks per theater; this constraint is the final arbiter
-- for concurrent writes and direct SQL, including movie-duration changes.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE movie_schedules
    ADD CONSTRAINT ex_movie_schedules_theater_window
    EXCLUDE USING gist (
        theater_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    );

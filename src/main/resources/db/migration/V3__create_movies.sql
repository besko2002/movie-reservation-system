-- V3: genres, movies and their many-to-many link (phase 3 - movies + genres).
-- Owned by the movies module; no other module writes to these tables.
CREATE TABLE genres (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(60) NOT NULL UNIQUE
);

CREATE TABLE movies (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(200) NOT NULL,
    description      TEXT,
    poster_url       VARCHAR(500),
    duration_minutes INT          NOT NULL CHECK (duration_minutes > 0),
    release_date     DATE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE movie_genres (
    movie_id BIGINT NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
    genre_id BIGINT NOT NULL REFERENCES genres(id) ON DELETE RESTRICT,
    PRIMARY KEY (movie_id, genre_id)
);

-- Case-insensitive title search (LIKE lower(title) ...).
CREATE INDEX idx_movies_lower_title ON movies (lower(title));

-- The genre -> movies direction of the join table (the PK already covers movie_id first).
CREATE INDEX idx_movie_genres_genre_id ON movie_genres (genre_id);

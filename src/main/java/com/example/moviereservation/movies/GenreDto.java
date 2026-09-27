package com.example.moviereservation.movies;

/**
 * Public view of a genre.
 *
 * @param id   genre id
 * @param name genre name
 */
public record GenreDto(Long id, String name) {

    static GenreDto from(Genre genre) {
        return new GenreDto(genre.getId(), genre.getName());
    }
}

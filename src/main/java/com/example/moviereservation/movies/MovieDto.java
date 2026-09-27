package com.example.moviereservation.movies;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Full movie view, including its genres.
 *
 * @param id              movie id
 * @param title           movie title
 * @param description     synopsis, may be {@code null}
 * @param posterUrl       poster image URL, may be {@code null}
 * @param durationMinutes running time in minutes
 * @param releaseDate     release date, may be {@code null}
 * @param genres          genres of the movie, sorted by name
 * @param createdAt       when the movie was added to the catalogue
 */
public record MovieDto(
        Long id,
        String title,
        String description,
        String posterUrl,
        int durationMinutes,
        LocalDate releaseDate,
        List<GenreDto> genres,
        OffsetDateTime createdAt
) {

    /** Must be called while the movie is still attached, because genres are lazy. */
    static MovieDto from(Movie movie) {
        List<GenreDto> genres = movie.getGenres().stream()
                .map(GenreDto::from)
                .sorted(Comparator.comparing(GenreDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new MovieDto(
                movie.getId(),
                movie.getTitle(),
                movie.getDescription(),
                movie.getPosterUrl(),
                movie.getDurationMinutes(),
                movie.getReleaseDate(),
                genres,
                movie.getCreatedAt());
    }
}

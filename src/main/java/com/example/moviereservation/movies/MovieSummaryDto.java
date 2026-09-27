package com.example.moviereservation.movies;

import java.time.LocalDate;

/**
 * Compact movie view used in list responses; genres are omitted on purpose to keep listing
 * queries flat (use {@link MovieDto} for the detail view).
 *
 * @param id              movie id
 * @param title           movie title
 * @param posterUrl       poster image URL, may be {@code null}
 * @param durationMinutes running time in minutes
 * @param releaseDate     release date, may be {@code null}
 */
public record MovieSummaryDto(
        Long id,
        String title,
        String posterUrl,
        int durationMinutes,
        LocalDate releaseDate
) {

    static MovieSummaryDto from(Movie movie) {
        return new MovieSummaryDto(
                movie.getId(),
                movie.getTitle(),
                movie.getPosterUrl(),
                movie.getDurationMinutes(),
                movie.getReleaseDate());
    }
}

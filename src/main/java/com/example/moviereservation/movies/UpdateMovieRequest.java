package com.example.moviereservation.movies;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Payload for a full movie update: every field is replaced, including the genre set.
 *
 * @param title           movie title, required
 * @param description     synopsis, optional
 * @param posterUrl       absolute http(s) poster URL, optional
 * @param durationMinutes running time in minutes, 1..600
 * @param releaseDate     release date, optional
 * @param genreIds        ids of existing genres that replace the current set
 */
public record UpdateMovieRequest(

        @NotBlank(message = "title must not be blank")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,

        @Size(max = 4000, message = "description must be at most 4000 characters")
        String description,

        @Size(max = 500, message = "posterUrl must be at most 500 characters")
        @Pattern(regexp = MovieValidation.URL_PATTERN, message = MovieValidation.URL_MESSAGE)
        String posterUrl,

        @NotNull(message = "durationMinutes must not be null")
        @Min(value = 1, message = "durationMinutes must be at least 1")
        @Max(value = 600, message = "durationMinutes must be at most 600")
        Integer durationMinutes,

        LocalDate releaseDate,

        Set<@NotNull(message = "genreIds must not contain null") Long> genreIds
) {

    /** Normalises blanks to {@code null} and never exposes a {@code null} genre id set. */
    public UpdateMovieRequest {
        title = title == null ? null : title.trim();
        description = MovieValidation.blankToNull(description);
        posterUrl = MovieValidation.blankToNull(posterUrl);
        genreIds = genreIds == null ? Set.of() : new LinkedHashSet<>(genreIds);
    }
}

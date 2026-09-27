package com.example.moviereservation.movies;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a genre.
 *
 * @param name genre name; stored trimmed and unique case-insensitively
 */
public record GenreRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 60, message = "name must be at most 60 characters")
        String name
) {

    /** Trims before validation so trailing blanks cannot smuggle in an over-long name. */
    public GenreRequest {
        name = name == null ? null : name.trim();
    }
}

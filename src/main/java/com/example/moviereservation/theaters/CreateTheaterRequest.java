package com.example.moviereservation.theaters;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payload for creating a theater; its seats are generated from the grid.
 *
 * @param name        theater name, required and unique ignoring case
 * @param location    where the theater is, optional
 * @param totalRows   number of rows, 1..26 (rows are labelled A, B, C ...)
 * @param seatsPerRow seats in each row, 1..50
 * @param vipRows     row labels that become VIP seats; case-insensitive, duplicates collapsed,
 *                    every label must exist in the generated grid
 */
public record CreateTheaterRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Size(max = 200, message = "location must be at most 200 characters")
        String location,

        @NotNull(message = "totalRows must not be null")
        @Min(value = 1, message = "totalRows must be at least 1")
        @Max(value = 26, message = "totalRows must be at most 26")
        Integer totalRows,

        @NotNull(message = "seatsPerRow must not be null")
        @Min(value = 1, message = "seatsPerRow must be at least 1")
        @Max(value = 50, message = "seatsPerRow must be at most 50")
        Integer seatsPerRow,

        List<@Pattern(regexp = TheaterValidation.ROW_LABEL_PATTERN,
                message = TheaterValidation.ROW_LABEL_MESSAGE) String> vipRows
) {

    /** Normalises blanks to {@code null} and never exposes a {@code null} VIP row list. */
    public CreateTheaterRequest {
        name = name == null ? null : name.trim();
        location = TheaterValidation.blankToNull(location);
        vipRows = vipRows == null ? List.of() : List.copyOf(vipRows);
    }
}

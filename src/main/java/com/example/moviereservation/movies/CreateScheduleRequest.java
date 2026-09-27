package com.example.moviereservation.movies;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Payload for creating a schedule (showtime).
 *
 * <p>{@code endTime} is intentionally absent: the server computes it from the movie duration plus
 * the configured cleaning buffer.
 *
 * @param movieId   id of an existing movie
 * @param theaterId id of an existing theater
 * @param startTime ISO-8601 instant with offset, for example {@code 2026-01-01T18:00:00Z}; must be
 *                  in the future
 * @param basePrice price of a NORMAL seat, &gt;= 0 with at most 2 decimals
 * @param vipPrice  price of a VIP seat, &gt;= {@code basePrice}
 */
public record CreateScheduleRequest(

        @NotNull(message = "movieId must not be null")
        Long movieId,

        @NotNull(message = "theaterId must not be null")
        Long theaterId,

        @NotNull(message = "startTime must not be null")
        OffsetDateTime startTime,

        @NotNull(message = "basePrice must not be null")
        @DecimalMin(value = "0.00", message = "basePrice must not be negative")
        @Digits(integer = 8, fraction = 2, message = "basePrice must have at most 2 decimals")
        BigDecimal basePrice,

        @NotNull(message = "vipPrice must not be null")
        @DecimalMin(value = "0.00", message = "vipPrice must not be negative")
        @Digits(integer = 8, fraction = 2, message = "vipPrice must have at most 2 decimals")
        BigDecimal vipPrice
) {
}

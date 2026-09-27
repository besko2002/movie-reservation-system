package com.example.moviereservation.seatreservation;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Payload for holding seats of one schedule.
 *
 * <p>Duplicates and the per-request maximum ({@code app.reservation.max-seats}) are rejected by
 * {@link SeatReservationService}, because the maximum is configurable and therefore cannot be a
 * bean-validation constant.
 *
 * @param scheduleId schedule to book in; must exist and must not have started
 * @param seatIds    seats to hold: at least one, no duplicates, all in the schedule's theater
 */
public record HoldSeatsRequest(

        @NotNull(message = "scheduleId must not be null")
        Long scheduleId,

        @NotEmpty(message = "seatIds must not be empty")
        List<Long> seatIds
) {
}

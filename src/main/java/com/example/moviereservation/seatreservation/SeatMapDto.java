package com.example.moviereservation.seatreservation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Seat map of one schedule: every seat of the theater it plays in, with its price and current
 * status.
 *
 * @param scheduleId schedule the map belongs to
 * @param theaterId  theater the schedule plays in
 * @param startTime  when the showtime starts (a client should not offer seats after that)
 * @param seats      every seat, ordered by row label then seat number
 */
public record SeatMapDto(
        Long scheduleId,
        Long theaterId,
        OffsetDateTime startTime,
        List<SeatMapSeatDto> seats
) {
}

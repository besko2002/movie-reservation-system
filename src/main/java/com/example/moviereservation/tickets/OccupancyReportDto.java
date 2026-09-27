package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * How full one showtime is, as reported by {@code GET /api/admin/reports/occupancy/{scheduleId}}.
 *
 * <p>{@code bookedSeats} are the paid seats ({@code CONFIRMED} reservations) and
 * {@code heldSeats} are the holds that are still live — an expired hold occupies nothing, so it is
 * counted as available even before the sweeper released its row. {@code occupancyPercent} therefore
 * deliberately only measures sold seats ({@code bookedSeats / capacity * 100}, scale 2): a hold is
 * not a sale.
 *
 * @param scheduleId       the showtime
 * @param movieTitle       movie that plays
 * @param theaterName      theater it plays in
 * @param startTime        when it starts
 * @param capacity         number of seats of that theater
 * @param bookedSeats      CONFIRMED (paid) seats
 * @param heldSeats        live, unexpired holds
 * @param availableSeats   {@code capacity - bookedSeats - heldSeats}, never negative
 * @param occupancyPercent {@code bookedSeats / capacity * 100}, scale 2 ({@code 0.00} for a theater
 *                         without seats)
 * @param revenue          sum of {@code total_price} of the PAID tickets of that schedule, scale 2
 */
public record OccupancyReportDto(
        Long scheduleId,
        String movieTitle,
        String theaterName,
        OffsetDateTime startTime,
        long capacity,
        long bookedSeats,
        long heldSeats,
        long availableSeats,
        BigDecimal occupancyPercent,
        BigDecimal revenue
) {
}

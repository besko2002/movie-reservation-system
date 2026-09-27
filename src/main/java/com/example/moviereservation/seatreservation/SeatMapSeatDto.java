package com.example.moviereservation.seatreservation;

import com.example.moviereservation.theaters.SeatType;

import java.math.BigDecimal;

/**
 * One seat in the seat map of a schedule.
 *
 * @param seatId     seat id, to be sent back in a hold request
 * @param rowLabel   row letter, A..Z
 * @param seatNumber seat number inside the row, starting at 1
 * @param type       NORMAL or VIP
 * @param price      what this schedule charges for that seat type
 * @param status     AVAILABLE, HELD or BOOKED
 */
public record SeatMapSeatDto(
        Long seatId,
        String rowLabel,
        int seatNumber,
        SeatType type,
        BigDecimal price,
        SeatStatus status
) {
}

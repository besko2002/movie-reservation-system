package com.example.moviereservation.seatreservation;

import com.example.moviereservation.theaters.SeatType;

import java.math.BigDecimal;

/**
 * One seat that was just held, or that belongs to an existing hold.
 *
 * @param reservationId id of the reservation row (used to release exactly this seat)
 * @param seatId        seat that is held
 * @param rowLabel      row letter, A..Z
 * @param seatNumber    seat number inside the row
 * @param type          NORMAL or VIP
 * @param price         price snapshot taken when the hold was created
 */
public record HeldSeatDto(
        Long reservationId,
        Long seatId,
        String rowLabel,
        int seatNumber,
        SeatType type,
        BigDecimal price
) {
}

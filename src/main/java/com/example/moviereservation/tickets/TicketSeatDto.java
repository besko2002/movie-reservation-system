package com.example.moviereservation.tickets;

import com.example.moviereservation.seatreservation.ReservationStatus;
import com.example.moviereservation.theaters.SeatType;

import java.math.BigDecimal;

/**
 * One seat of a ticket, as the API shows it.
 *
 * @param reservationId reservation row behind the seat
 * @param seatId        seat id
 * @param rowLabel      row letter, A..Z
 * @param seatNumber    seat number inside the row
 * @param type          NORMAL or VIP
 * @param price         price snapshot charged for this seat
 * @param status        HELD while the ticket waits for payment, CONFIRMED once paid, RELEASED after
 *                      a cancellation or refund
 */
public record TicketSeatDto(
        Long reservationId,
        Long seatId,
        String rowLabel,
        int seatNumber,
        SeatType type,
        BigDecimal price,
        ReservationStatus status
) {
}

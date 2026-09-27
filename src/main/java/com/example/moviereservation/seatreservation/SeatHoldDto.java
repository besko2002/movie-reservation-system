package com.example.moviereservation.seatreservation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Result of holding seats: one reservation row per seat, but a single hold window and total.
 *
 * @param reservationIds ids of the created reservations, in the order of {@code seats}
 * @param scheduleId     schedule the seats belong to
 * @param seats          the held seats with their prices
 * @param totalPrice     sum of the seat prices, the amount phase 7 will charge
 * @param expiresAt      when the whole hold expires ({@code app.reservation.hold-minutes})
 */
public record SeatHoldDto(
        List<Long> reservationIds,
        Long scheduleId,
        List<HeldSeatDto> seats,
        BigDecimal totalPrice,
        OffsetDateTime expiresAt
) {
}

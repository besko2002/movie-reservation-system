package com.example.moviereservation.seatreservation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One reservation row as other modules and the API see it.
 *
 * <p>This is the shape phase 7 works with: it reads the held seats of a user for a schedule, sums
 * {@code price}, and later confirms or releases them by id.
 *
 * @param id         reservation id
 * @param userId     owner of the reservation
 * @param scheduleId schedule the seat belongs to
 * @param seatId     reserved seat
 * @param ticketId   ticket this reservation was paid with; {@code null} while it is only held
 * @param price      price snapshot taken when the hold was created
 * @param status     HELD, CONFIRMED or RELEASED
 * @param expiresAt  end of the hold window; {@code null} for CONFIRMED and RELEASED rows
 * @param createdAt  when the reservation was created
 */
public record SeatReservationDto(
        Long id,
        Long userId,
        Long scheduleId,
        Long seatId,
        Long ticketId,
        BigDecimal price,
        ReservationStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {

    static SeatReservationDto from(SeatReservation reservation) {
        return new SeatReservationDto(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getScheduleId(),
                reservation.getSeatId(),
                reservation.getTicketId(),
                reservation.getPrice(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                reservation.getCreatedAt());
    }
}

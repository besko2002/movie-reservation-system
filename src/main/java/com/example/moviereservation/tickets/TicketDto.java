package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full ticket view, including the seats it covers.
 *
 * @param id                ticket id
 * @param userId            owner
 * @param scheduleId        showtime
 * @param movieTitle        movie that plays
 * @param scheduleStartTime when the showtime starts (the cancellation cutoff counts back from it)
 * @param status            PENDING_PAYMENT, PAID, CANCELLED, REFUNDED or EXPIRED
 * @param totalPrice        sum of the seat prices, as charged
 * @param currency          ISO 4217 currency the payment was created in
 * @param seatCount         number of seats on this ticket
 * @param seats             the seats, oldest reservation first
 * @param createdAt         when the checkout was started
 * @param paidAt            when the payment landed; {@code null} until then
 * @param cancelledAt       when it was cancelled, expired or refunded; {@code null} otherwise
 */
public record TicketDto(
        Long id,
        Long userId,
        Long scheduleId,
        String movieTitle,
        OffsetDateTime scheduleStartTime,
        TicketStatus status,
        BigDecimal totalPrice,
        String currency,
        int seatCount,
        List<TicketSeatDto> seats,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime cancelledAt
) {
}

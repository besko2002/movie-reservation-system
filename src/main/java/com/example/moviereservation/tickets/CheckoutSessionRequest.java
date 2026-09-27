package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything a {@link PaymentGateway} needs to open a hosted payment page for one ticket. The
 * currency, the return URLs and the provider credentials are the gateway's business, not the
 * caller's.
 *
 * @param ticketId   ticket being paid; travels back on the event as the client reference
 * @param userId     buyer, for the event metadata
 * @param scheduleId showtime, for the event metadata
 * @param movieTitle shown on the payment page
 * @param totalPrice sum of {@code lineItems}, used as a cross-check and for the refund amount
 * @param lineItems  one entry per seat
 * @param expiresAt  when the payment page must stop accepting money. It is the same instant the
 *                   buyer's seat holds were extended to (see {@link TicketCheckoutWindow}), so the
 *                   seats cannot be swept away while the page is still payable; Stripe requires it
 *                   to be 30 minutes to 24 hours in the future
 */
public record CheckoutSessionRequest(
        Long ticketId,
        Long userId,
        Long scheduleId,
        String movieTitle,
        BigDecimal totalPrice,
        List<CheckoutLineItem> lineItems,
        OffsetDateTime expiresAt
) {
}

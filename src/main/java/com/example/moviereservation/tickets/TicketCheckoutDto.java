package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Answer of {@code POST /api/tickets}: the created (or re-created) ticket plus where to pay.
 *
 * @param ticketId    ticket that was created
 * @param status      always {@code PENDING_PAYMENT} here
 * @param totalPrice  amount the hosted checkout asks for
 * @param currency    ISO 4217 currency of {@code totalPrice}
 * @param checkoutUrl absolute URL of the hosted payment page — send the buyer there
 * @param expiresAt   when that page stops accepting a payment. The buyer's seat holds were extended
 *                    to exactly this instant, so it is also how long the seats stay reserved;
 *                    {@code null} only when a provider could not tell (repeated checkout of a
 *                    session this server did not create)
 */
public record TicketCheckoutDto(
        Long ticketId,
        TicketStatus status,
        BigDecimal totalPrice,
        String currency,
        String checkoutUrl,
        OffsetDateTime expiresAt
) {
}

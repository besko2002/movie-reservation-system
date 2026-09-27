package com.example.moviereservation.tickets;

import java.time.OffsetDateTime;

/**
 * A hosted payment page created by a {@link PaymentGateway}.
 *
 * @param sessionId   provider id of the session, stored in {@code tickets.stripe_session_id}
 * @param checkoutUrl absolute URL the client must send the buyer to
 * @param expiresAt   when the session stops accepting a payment; {@code null} when unknown
 */
public record PaymentSessionDto(
        String sessionId,
        String checkoutUrl,
        OffsetDateTime expiresAt
) {
}

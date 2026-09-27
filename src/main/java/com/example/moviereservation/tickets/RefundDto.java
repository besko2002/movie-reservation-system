package com.example.moviereservation.tickets;

import java.math.BigDecimal;

/**
 * A refund created by a {@link PaymentGateway}.
 *
 * @param refundId        provider id of the refund
 * @param paymentIntentId payment that was refunded
 * @param amount          refunded amount in the ticket's currency (major units)
 * @param status          provider status, e.g. {@code succeeded} or {@code pending}
 */
public record RefundDto(
        String refundId,
        String paymentIntentId,
        BigDecimal amount,
        String status
) {
}

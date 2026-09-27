package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * The {@link PaymentGateway} used while no payment provider is configured
 * ({@code app.stripe.secret-key} is empty).
 *
 * <p>The point of this class is that the application <strong>starts and stays healthy</strong>
 * without Stripe credentials: browsing, seat maps and seat holds keep working, and only the three
 * operations that genuinely need a provider answer
 * <strong>503 Service Unavailable</strong> with a normal {@link com.example.moviereservation.common.ApiError}
 * body naming the missing configuration — never a 500 with a stack trace.
 *
 * <p>Webhook deliveries are not affected: they are authenticated by {@link StripeWebhookVerifier},
 * which only needs {@code app.stripe.webhook-secret}. A server that has a webhook secret but no
 * secret key therefore still applies genuine deliveries, and still rejects forged ones with a 400.
 */
class DisabledPaymentGateway implements PaymentGateway {

    static final String MESSAGE =
            "Online payment is not configured on this server (app.stripe.secret-key / STRIPE_SECRET_KEY is empty); "
                    + "ticket checkout and refunds are unavailable";

    private static final Logger log = LoggerFactory.getLogger(DisabledPaymentGateway.class);

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public PaymentSessionDto createCheckoutSession(CheckoutSessionRequest request) {
        log.warn("Checkout requested for ticket {} but no payment provider is configured", request.ticketId());
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public RefundDto refund(String paymentIntentId, BigDecimal amount) {
        log.warn("Refund requested for payment intent {} but no payment provider is configured", paymentIntentId);
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public PaymentSessionDto retrieveCheckoutSession(String sessionId) {
        log.warn("Session {} was requested but no payment provider is configured", sessionId);
        throw new ServiceUnavailableException(MESSAGE);
    }
}

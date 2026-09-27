package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ServiceUnavailableException;

import java.math.BigDecimal;

/**
 * This project's payment port. Everything the tickets module needs from a payment provider is
 * declared here, in this project's own vocabulary; nothing outside this file and
 * {@link StripePaymentGateway} knows that the provider is Stripe.
 *
 * <p>Why a port at all:
 * <ul>
 *   <li>The build must never reach the real Stripe API. Tests bind a deterministic fake
 *       implementation, so no test needs credentials or a network.</li>
 *   <li>Without credentials the application still starts: {@link PaymentGatewayConfig} then
 *       publishes {@link DisabledPaymentGateway}, whose methods raise
 *       {@link ServiceUnavailableException} (HTTP 503) instead of failing obscurely.</li>
 * </ul>
 *
 * <p>Webhook verification is deliberately <strong>not</strong> part of this port: it needs the
 * webhook secret only, never the API key, so it lives in {@link StripeWebhookVerifier} and keeps
 * working on a server that has a webhook secret but no secret key.
 *
 * @see StripePaymentGateway real implementation, active only when {@code app.stripe.secret-key} is set
 * @see DisabledPaymentGateway fallback that answers 503
 */
public interface PaymentGateway {

    /**
     * Creates a hosted payment page for one ticket.
     *
     * @param request what is being paid for (ticket, seats, total)
     * @return the created session: its provider id, the URL to send the user to, and its expiry
     * @throws ServiceUnavailableException when no payment provider is configured, or the provider
     *                                    could not be reached
     */
    PaymentSessionDto createCheckoutSession(CheckoutSessionRequest request);

    /**
     * Refunds (part of) a captured payment.
     *
     * @param paymentIntentId provider id of the payment to refund
     * @param amount          amount to refund in the ticket's currency; a full refund for phase 7
     * @return the created refund
     * @throws ServiceUnavailableException when no payment provider is configured, or the provider
     *                                    could not be reached
     */
    RefundDto refund(String paymentIntentId, BigDecimal amount);

    /**
     * Reads back a session created earlier, so a repeated checkout can hand the buyer the
     * <em>same</em> payment page instead of opening a second one (see
     * {@link TicketService#checkout}). Only the session id is stored in {@code tickets}, which is
     * why its URL and expiry have to be asked for again.
     *
     * @param sessionId provider id of the session, from {@code tickets.stripe_session_id}
     * @return the session as it is now
     * @throws ServiceUnavailableException when no payment provider is configured, or the provider
     *                                    could not be reached
     */
    PaymentSessionDto retrieveCheckoutSession(String sessionId);

    /**
     * @return whether payments are actually configured; {@code false} for
     *         {@link DisabledPaymentGateway}
     */
    boolean isEnabled();
}

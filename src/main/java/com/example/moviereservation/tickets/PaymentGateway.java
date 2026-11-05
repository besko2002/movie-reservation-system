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
     * Refunds with a stable idempotency key, so a retried refund after a database failure returns
     * the <em>same</em> provider refund instead of moving money twice.
     *
     * <p>The default keeps simple implementations working; a real provider implementation should
     * override it.
     *
     * @param paymentIntentId provider id of the payment to refund
     * @param amount          amount to refund in the ticket's currency
     * @param idempotencyKey  stable key for this refund, safe to reuse on every retry
     * @return the created (or previously created) refund
     * @throws ServiceUnavailableException when no payment provider is configured, or the provider
     *                                    could not be reached
     */
    default RefundDto refund(String paymentIntentId, BigDecimal amount, String idempotencyKey) {
        return refund(paymentIntentId, amount);
    }

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
     * Expires a hosted payment page immediately, so a ticket that is being cancelled cannot still
     * be paid through the page its buyer already has open.
     *
     * <p>Best effort from the caller's point of view: the cancellation must still succeed when the
     * provider is down, because the webhook safety net refunds any payment that arrives late.
     *
     * @param sessionId provider id of the session to expire
     * @throws ServiceUnavailableException when no payment provider is configured, or the provider
     *                                    could not be reached
     */
    void expireCheckoutSession(String sessionId);

    /**
     * @return whether payments are actually configured; {@code false} for
     *         {@link DisabledPaymentGateway}
     */
    boolean isEnabled();
}

package com.example.moviereservation.tickets;

/**
 * A verified webhook delivery, reduced to the fields this module acts on. Keeping the provider
 * payload out of {@link TicketService} is what makes the webhook testable without Stripe.
 *
 * @param id              provider event id; same value on every redelivery of the same event
 * @param type            provider event type, e.g. {@code checkout.session.completed}
 * @param sessionId       checkout session the event is about; {@code null} for charge events
 * @param paymentIntentId payment behind it; {@code null} while no payment exists yet
 * @param ticketId        ticket id taken from the session's client reference / metadata;
 *                        {@code null} when the event does not carry one
 * @param paymentStatus   Stripe's {@code payment_status} ({@code paid}, {@code unpaid}, ...);
 *                        {@code null} on events that do not carry one
 * @param amountTotal     amount the buyer paid, in the currency's minor unit (cents for USD);
 *                        {@code null} when the event does not carry it
 * @param currency        lower-case ISO currency of the payment; {@code null} when not carried
 * @param amount          charged amount on a charge event, in minor units; {@code null} otherwise
 * @param amountRefunded  total already refunded on a charge event, in minor units; {@code null}
 *                        otherwise
 */
public record PaymentEvent(
        String id,
        String type,
        String sessionId,
        String paymentIntentId,
        Long ticketId,
        String paymentStatus,
        Long amountTotal,
        String currency,
        Long amount,
        Long amountRefunded
) {

    /** Payment succeeded: the buyer completed the hosted checkout. */
    public static final String CHECKOUT_SESSION_COMPLETED = "checkout.session.completed";

    /** A delayed payment method finished successfully after the session was completed. */
    public static final String CHECKOUT_SESSION_ASYNC_PAYMENT_SUCCEEDED =
            "checkout.session.async_payment_succeeded";

    /** A delayed payment method failed for good after the session was completed. */
    public static final String CHECKOUT_SESSION_ASYNC_PAYMENT_FAILED =
            "checkout.session.async_payment_failed";

    /** The hosted checkout was never completed and is now dead. */
    public static final String CHECKOUT_SESSION_EXPIRED = "checkout.session.expired";

    /** The payment attempt failed for good. */
    public static final String PAYMENT_INTENT_PAYMENT_FAILED = "payment_intent.payment_failed";

    /** Money was given back (through this API's refund, or from the Stripe dashboard). */
    public static final String CHARGE_REFUNDED = "charge.refunded";

    /** Convenience constructor for events whose payload carries no money details. */
    public PaymentEvent(String id, String type, String sessionId, String paymentIntentId, Long ticketId) {
        this(id, type, sessionId, paymentIntentId, ticketId, null, null, null, null, null);
    }
}

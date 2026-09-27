package com.example.moviereservation.tickets;

/**
 * A verified webhook delivery, reduced to the four things this module acts on. Keeping the provider
 * payload out of {@link TicketService} is what makes the webhook testable without Stripe.
 *
 * @param id              provider event id; same value on every redelivery of the same event
 * @param type            provider event type, e.g. {@code checkout.session.completed}
 * @param sessionId       checkout session the event is about; {@code null} for charge events
 * @param paymentIntentId payment behind it; {@code null} while no payment exists yet
 * @param ticketId        ticket id taken from the session's client reference / metadata;
 *                        {@code null} when the event does not carry one
 */
public record PaymentEvent(
        String id,
        String type,
        String sessionId,
        String paymentIntentId,
        Long ticketId
) {

    /** Payment succeeded: the buyer completed the hosted checkout. */
    public static final String CHECKOUT_SESSION_COMPLETED = "checkout.session.completed";

    /** The hosted checkout was never completed and is now dead. */
    public static final String CHECKOUT_SESSION_EXPIRED = "checkout.session.expired";

    /** The payment attempt failed for good. */
    public static final String PAYMENT_INTENT_PAYMENT_FAILED = "payment_intent.payment_failed";

    /** Money was given back (through this API's refund, or from the Stripe dashboard). */
    public static final String CHARGE_REFUNDED = "charge.refunded";
}

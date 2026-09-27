package com.example.moviereservation.tickets;

/**
 * Lifecycle of a ticket. Mirrors the {@code CHECK} constraint of {@code tickets.status}.
 *
 * <pre>
 *   PENDING_PAYMENT --(checkout.session.completed)--&gt; PAID --(charge.refunded | DELETE)--&gt; REFUNDED
 *          |  |                                        |
 *          |  +--(checkout.session.expired)-----------&gt; EXPIRED
 *          +-----(DELETE | payment_intent.payment_failed)--&gt; CANCELLED
 * </pre>
 *
 * Every transition is guarded by the current status, which is what makes the webhook idempotent:
 * a redelivered Stripe event finds the ticket already in its target state and changes nothing.
 */
public enum TicketStatus {

    /** A Checkout Session exists, the money has not arrived yet; the seats are still only HELD. */
    PENDING_PAYMENT,

    /** Paid: the seats of this ticket are CONFIRMED. */
    PAID,

    /** Given up before payment (user cancelled, or the payment failed); seats released. */
    CANCELLED,

    /** Paid and then refunded in full (user cancelled before the cutoff); seats released. */
    REFUNDED,

    /** The Checkout Session expired before it was paid; seats released. */
    EXPIRED;

    /** @return whether this ticket still waits for money */
    public boolean isPending() {
        return this == PENDING_PAYMENT;
    }

    /** @return whether this ticket is finished, so no state change may be applied anymore */
    public boolean isFinal() {
        return this == CANCELLED || this == REFUNDED || this == EXPIRED;
    }
}

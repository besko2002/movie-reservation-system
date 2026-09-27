package com.example.moviereservation.tickets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * How long a buyer has to pay a checkout — {@code app.ticket.checkout-minutes}.
 *
 * <p>One value drives two things that must agree, which is the whole reason this tiny type exists:
 * <ul>
 *   <li>the Stripe Checkout Session's {@code expires_at}, so the payment page dies at a known
 *       instant instead of Stripe's 24 hour default, and</li>
 *   <li>the new {@code expires_at} of the seat holds the ticket was opened for, so
 *       {@code SeatReservationExpirySweeper} cannot free seats that are still payable.</li>
 * </ul>
 * If the two ever drifted apart, a buyer could pay for seats somebody else had meanwhile taken.
 *
 * <p><strong>Fail fast:</strong> Stripe rejects an {@code expires_at} that is not between 30 minutes
 * and 24 hours in the future, and a misconfigured deployment must not discover that at the first
 * real checkout. The constructor therefore refuses anything outside {@value #MIN_MINUTES}..
 * {@value #MAX_MINUTES} minutes and the application context fails to start.
 */
@Component
public class TicketCheckoutWindow {

    /** Stripe's lower bound for a Checkout Session {@code expires_at}: 30 minutes. */
    public static final int MIN_MINUTES = 30;

    /** Stripe's upper bound for a Checkout Session {@code expires_at}: 24 hours. */
    public static final int MAX_MINUTES = 24 * 60;

    private final int minutes;

    public TicketCheckoutWindow(@Value("${app.ticket.checkout-minutes}") int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException(
                    ("app.ticket.checkout-minutes must be between %d and %d minutes because Stripe only accepts a "
                            + "Checkout Session expires_at between 30 minutes and 24 hours in the future, but it "
                            + "is %d")
                            .formatted(MIN_MINUTES, MAX_MINUTES, minutes));
        }
        this.minutes = minutes;
    }

    /** @return the configured payment window in minutes */
    public int getMinutes() {
        return minutes;
    }

    /**
     * The instant a checkout started at {@code now} stops being payable.
     *
     * <p>Truncated to whole seconds on purpose: Stripe's {@code expires_at} is epoch
     * <em>seconds</em>, so this is the exact instant the session and the seat holds both carry, and
     * the value the API answers with.
     *
     * @param now when the checkout is created
     * @return {@code now + app.ticket.checkout-minutes}, truncated to seconds
     */
    public OffsetDateTime expiryFrom(OffsetDateTime now) {
        return now.plusMinutes(minutes).truncatedTo(ChronoUnit.SECONDS);
    }
}

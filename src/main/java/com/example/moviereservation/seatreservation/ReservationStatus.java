package com.example.moviereservation.seatreservation;

/**
 * Lifecycle of a single seat reservation.
 *
 * <p>{@link #HELD} and {@link #CONFIRMED} are the two <em>active</em> states: the partial unique
 * index {@code ux_active_seat_per_schedule} covers exactly these two, which is what makes double
 * booking impossible. {@link #RELEASED} rows are outside the index, so a released seat can be
 * booked again.
 */
public enum ReservationStatus {

    /** Temporarily reserved for a user until {@code expiresAt}; not paid yet. */
    HELD,

    /** Paid (phase 7 flips a hold to this when the ticket is paid). */
    CONFIRMED,

    /** Given up: expired, released by the user, or cancelled/refunded in phase 7. */
    RELEASED;

    /** @return whether this status occupies the seat (i.e. is covered by the partial unique index) */
    public boolean isActive() {
        return this == HELD || this == CONFIRMED;
    }
}

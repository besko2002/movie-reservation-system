package com.example.moviereservation.theaters;

/**
 * Category of a seat inside a theater.
 *
 * <p>The schedules module prices {@link #NORMAL} seats with the base price and {@link #VIP} seats
 * with the higher VIP price.
 */
public enum SeatType {

    /** Ordinary seat, priced with the schedule's base price. */
    NORMAL,

    /** Premium seat, priced with the schedule's VIP price. */
    VIP
}

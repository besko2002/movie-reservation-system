package com.example.moviereservation.seatreservation;

/**
 * Status of a seat in the seat map of one schedule, as a client sees it.
 *
 * <p>Deliberately narrower than {@link ReservationStatus}: a client only needs to know whether it
 * may pick the seat. An expired hold is reported as {@link #AVAILABLE} even before the sweeper has
 * released it.
 */
public enum SeatStatus {

    /** Free: no reservation, or only released/expired ones. */
    AVAILABLE,

    /** Someone holds the seat and the hold has not expired yet. */
    HELD,

    /** Paid for (a CONFIRMED reservation). */
    BOOKED
}

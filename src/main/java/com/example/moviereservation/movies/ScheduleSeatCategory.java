package com.example.moviereservation.movies;

/**
 * Price category of a seat, as far as a schedule is concerned.
 *
 * <p>Deliberately declared inside the movies module: the seat reservation and ticket modules can
 * price a seat through {@link ScheduleService#getPrice(Long, ScheduleSeatCategory)} without the
 * movies module importing the theaters module's {@code SeatType}. The names match the seat types
 * one-to-one, so a caller that already holds a seat type can map it with
 * {@link #of(String)}.
 */
public enum ScheduleSeatCategory {

    /** Ordinary seat: charged the schedule's base price. */
    NORMAL,

    /** Premium seat: charged the schedule's VIP price. */
    VIP;

    /**
     * Maps a seat type name (for example the theaters module's {@code SeatType.name()}) to a
     * price category.
     *
     * @param seatTypeName seat type name, case-insensitive
     * @return the matching category, {@link #NORMAL} for anything that is not VIP
     */
    public static ScheduleSeatCategory of(String seatTypeName) {
        return VIP.name().equalsIgnoreCase(seatTypeName) ? VIP : NORMAL;
    }
}

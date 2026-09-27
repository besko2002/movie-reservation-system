package com.example.moviereservation.movies;

import com.example.moviereservation.common.ConflictException;

/**
 * Seam that decides whether a schedule may be deleted (or moved in time).
 *
 * <p>From phase 6 on, a schedule is referenced by seat reservations and by the tickets built from
 * them. Deleting such a schedule must be refused with a 409 instead of destroying sold tickets.
 *
 * <p>Phase 5 publishes <strong>no</strong> bean of this type, so {@link ScheduleService} allows
 * every deletion. A later phase adds a Spring bean implementing this interface (for example in the
 * seat reservation module) that throws {@link ConflictException} when the schedule has active or
 * confirmed reservations; {@code ScheduleService} consults it through an
 * {@code ObjectProvider} and picks it up automatically, with no other code change.
 *
 * <p>Mirrors the theaters module's {@code SeatUsagePolicy} seam from phase 4.
 */
public interface ScheduleUsagePolicy {

    /**
     * Called right before the schedule {@code scheduleId} is deleted.
     *
     * @param scheduleId schedule that is about to disappear
     * @throws ConflictException when the schedule is in use and must be kept
     */
    void checkScheduleMayBeDeleted(Long scheduleId);

    /**
     * Called right before the schedule {@code scheduleId} gets a different theater, movie or start
     * time. Existing reservations point at seats of the old theater and were sold for the old show,
     * so such a change must be refused while the schedule is in use.
     *
     * @param scheduleId schedule that is about to be moved
     * @throws ConflictException when the schedule is in use and must stay where it is
     */
    default void checkScheduleMayBeMoved(Long scheduleId) {
    }
}

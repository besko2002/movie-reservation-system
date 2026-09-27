package com.example.moviereservation.theaters;

import com.example.moviereservation.common.ConflictException;

/**
 * Seam that decides whether a theater may be deleted.
 *
 * <p>The theaters module owns theaters, but other modules reference them by id: from phase 5 on a
 * movie schedule points at a {@code theaters(id)} row with {@code ON DELETE RESTRICT}. Deleting a
 * theater that is still scheduled must therefore be answered with a 409 instead of letting the
 * foreign key surface as a 500.
 *
 * <p>The theaters module never learns what a schedule is: it only consults this port. The movies
 * module publishes the implementation ({@code ScheduleTheaterUsagePolicy}), which answers through
 * {@code ScheduleService}, so no cross-module repository or entity is touched. When no bean is
 * published the deletion is simply allowed, exactly like {@link SeatUsagePolicy}.
 *
 * @see SeatUsagePolicy
 */
public interface TheaterUsagePolicy {

    /**
     * Called right before {@code theaterId} is deleted.
     *
     * @param theaterId theater that is about to be deleted
     * @throws ConflictException when something still references the theater
     */
    void checkTheaterMayBeDeleted(Long theaterId);
}

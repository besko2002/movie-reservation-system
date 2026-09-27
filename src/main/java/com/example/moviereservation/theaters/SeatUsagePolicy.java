package com.example.moviereservation.theaters;

import com.example.moviereservation.common.ConflictException;

/**
 * Seam that decides whether the seats of a theater may be thrown away and regenerated.
 *
 * <p>Regenerating a grid deletes every existing {@code seats} row and inserts new ones with new
 * ids. From phase 6 on, seats are referenced by seat reservations (and through them by tickets), so
 * regenerating a theater that is already booked must be refused with a 409 instead of cascading the
 * deletion into sold tickets.
 *
 * <p>Phase 4 ships {@code TheaterService}'s built-in permissive fallback: nothing references a seat
 * yet, so every regeneration is allowed. A later phase adds a Spring bean implementing this
 * interface (for example in the seat reservation module) that throws {@link ConflictException} when
 * the theater has active or confirmed reservations; {@code TheaterService} picks it up
 * automatically, and no other code has to change.
 */
public interface SeatUsagePolicy {

    /**
     * Called right before the seats of {@code theaterId} are deleted and regenerated.
     *
     * @param theaterId theater whose seats are about to be replaced
     * @throws ConflictException when the existing seats are in use and must not disappear
     */
    void checkSeatsMayBeRegenerated(Long theaterId);
}

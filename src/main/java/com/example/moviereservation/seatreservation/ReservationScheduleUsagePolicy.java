package com.example.moviereservation.seatreservation;

import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.movies.ScheduleUsagePolicy;
import org.springframework.stereotype.Component;

/**
 * Seat-reservation implementation of the movies module's deletion seam
 * {@link ScheduleUsagePolicy}: a schedule whose seats are held or booked must not be deleted.
 *
 * <p>This closes the seam that phase 5 left open, in the same shape as the movies module's
 * {@code ScheduleTheaterUsagePolicy}: the movies module keeps depending on its own port only, and
 * this adapter answers through {@link SeatReservationService} — the public API of this module — so
 * no repository or entity crosses a module boundary.
 *
 * <p>Expired holds do not count as usage, so a schedule nobody actually booked stays deletable. When
 * the deletion is allowed, this adapter first drops its own finished history for that schedule
 * (RELEASED rows that were never paid), because {@code seat_reservations.schedule_id} is
 * {@code ON DELETE RESTRICT} and would otherwise turn the deletion into a database error.
 * Reservations that belong to a ticket are never discarded — they make the deletion a 409.
 */
@Component
class ReservationScheduleUsagePolicy implements ScheduleUsagePolicy {

    private final SeatReservationService seatReservationService;

    ReservationScheduleUsagePolicy(SeatReservationService seatReservationService) {
        this.seatReservationService = seatReservationService;
    }

    @Override
    public void checkScheduleMayBeDeleted(Long scheduleId) {
        long active = seatReservationService.countActiveReservationsForSchedule(scheduleId);
        if (active > 0) {
            throw new ConflictException(
                    ("Schedule %d still has %d active seat reservation(s) (held or booked); "
                            + "they must be released before the schedule can be deleted")
                            .formatted(scheduleId, active));
        }
        long ticketed = seatReservationService.countTicketedReservationsForSchedule(scheduleId);
        if (ticketed > 0) {
            throw new ConflictException(
                    ("Schedule %d still has %d seat reservation(s) that belong to a ticket; it cannot be deleted "
                            + "without losing that history")
                            .formatted(scheduleId, ticketed));
        }
        seatReservationService.discardReleasedReservationsForSchedule(scheduleId);
    }

    @Override
    public void checkScheduleMayBeMoved(Long scheduleId) {
        long inUse = seatReservationService.countActiveReservationsForSchedule(scheduleId)
                + seatReservationService.countTicketedReservationsForSchedule(scheduleId);
        if (inUse > 0) {
            throw new ConflictException(
                    ("Schedule %d has %d seat reservation(s); its theater, movie and start time can no longer "
                            + "be changed (prices still can)")
                            .formatted(scheduleId, inUse));
        }
    }
}

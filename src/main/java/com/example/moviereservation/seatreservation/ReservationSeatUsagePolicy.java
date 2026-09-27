package com.example.moviereservation.seatreservation;

import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.theaters.SeatDto;
import com.example.moviereservation.theaters.SeatUsagePolicy;
import com.example.moviereservation.theaters.TheaterService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seat-reservation implementation of the theaters module's regeneration seam
 * {@link SeatUsagePolicy}: the seat grid of a theater must not be thrown away while any of its
 * seats is held or booked, because regeneration deletes every {@code seats} row and the
 * reservations point at those ids.
 *
 * <p>The theater's seat ids are read through {@link TheaterService#listSeats(Long)} — the theaters
 * module's public API — and then checked against this module's own rows, so neither module touches
 * the other's repository. Expired holds do not count as usage.
 *
 * <p>When regeneration <em>is</em> allowed, this adapter also drops its own finished history for
 * those seats (RELEASED rows that were never paid). That is necessary rather than cosmetic:
 * {@code seat_reservations.seat_id} is {@code ON DELETE RESTRICT}, so a single released hold would
 * otherwise make the grid unchangeable forever. Reservations that belong to a ticket are never
 * discarded — they make the regeneration a 409 instead.
 */
@Component
class ReservationSeatUsagePolicy implements SeatUsagePolicy {

    private final SeatReservationService seatReservationService;
    private final TheaterService theaterService;

    ReservationSeatUsagePolicy(SeatReservationService seatReservationService, TheaterService theaterService) {
        this.seatReservationService = seatReservationService;
        this.theaterService = theaterService;
    }

    @Override
    public void checkSeatsMayBeRegenerated(Long theaterId) {
        List<Long> seatIds = theaterService.listSeats(theaterId).stream().map(SeatDto::id).toList();
        long active = seatReservationService.countActiveReservationsForSeats(seatIds);
        if (active > 0) {
            throw new ConflictException(
                    ("Theater %d has %d active seat reservation(s) (held or booked); its seat grid cannot be "
                            + "changed until they are released")
                            .formatted(theaterId, active));
        }
        long ticketed = seatReservationService.countTicketedReservationsForSeats(seatIds);
        if (ticketed > 0) {
            throw new ConflictException(
                    ("Theater %d has %d seat reservation(s) that belong to a ticket; its seat grid cannot be "
                            + "changed without losing that history")
                            .formatted(theaterId, ticketed));
        }
        // Nothing is in use and nothing was ever paid: the leftover released holds may go, otherwise
        // the ON DELETE RESTRICT foreign key would block the deletion of the old seats.
        seatReservationService.discardReleasedReservationsForSeats(seatIds);
    }
}

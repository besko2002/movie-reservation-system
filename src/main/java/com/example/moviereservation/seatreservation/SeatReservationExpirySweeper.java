package com.example.moviereservation.seatreservation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Eager half of hold expiry: every {@code app.reservation.sweep-ms} milliseconds, HELD rows whose
 * {@code expiresAt} has passed become RELEASED, which takes them out of the partial unique index so
 * their seats can be booked again.
 *
 * <p>Correctness does not depend on this job: {@link SeatReservationService} already treats an
 * expired hold as free (the seat map reports AVAILABLE, and a hold request releases the expired rows
 * of the seats it wants before inserting). The sweeper only keeps the table tidy and makes the
 * change visible to anything that reads {@code status} directly.
 */
@Component
public class SeatReservationExpirySweeper {

    private final SeatReservationService seatReservationService;

    public SeatReservationExpirySweeper(SeatReservationService seatReservationService) {
        this.seatReservationService = seatReservationService;
    }

    /**
     * Releases the holds that have expired.
     *
     * @return how many holds were released (for tests; the scheduler ignores it)
     */
    @Scheduled(fixedDelayString = "${app.reservation.sweep-ms}", initialDelayString = "${app.reservation.sweep-ms}")
    public int sweep() {
        return seatReservationService.releaseExpiredHolds();
    }
}

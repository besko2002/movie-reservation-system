package com.example.moviereservation.seatreservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Module-internal persistence for {@link SeatReservation}; other modules go through
 * {@link SeatReservationService}.
 *
 * <p>The finders deliberately filter by status only and never by {@code expiresAt}: whether a HELD
 * row still occupies its seat is decided in Java by {@link SeatReservation#isActiveAt}, so the
 * "expired holds are free" rule lives in exactly one place. The bulk updates below are the eager
 * half of the same rule (the sweeper and the pre-insert cleanup).
 */
interface SeatReservationRepository extends JpaRepository<SeatReservation, Long> {

    /** Every non-released row of a schedule (seat map, usage policies). */
    List<SeatReservation> findByScheduleIdAndStatusIn(Long scheduleId, Collection<ReservationStatus> statuses);

    /** Non-released rows of a schedule restricted to the requested seats (hold pre-check). */
    List<SeatReservation> findByScheduleIdAndSeatIdInAndStatusIn(
            Long scheduleId, Collection<Long> seatIds, Collection<ReservationStatus> statuses);

    /** Non-released rows of one user, oldest first. */
    List<SeatReservation> findByUserIdAndStatusInOrderByIdAsc(Long userId, Collection<ReservationStatus> statuses);

    /** Non-released rows of one user inside one schedule, oldest first. */
    List<SeatReservation> findByUserIdAndScheduleIdAndStatusInOrderByIdAsc(
            Long userId, Long scheduleId, Collection<ReservationStatus> statuses);

    /** Reservations that belong to one ticket (phase 7). */
    List<SeatReservation> findByTicketIdOrderByIdAsc(Long ticketId);

    /** Non-released rows on any of these seats, across all schedules (seat regeneration seam). */
    List<SeatReservation> findBySeatIdInAndStatusIn(
            Collection<Long> seatIds, Collection<ReservationStatus> statuses);

    /** Rows on those seats that already belong to a ticket; their history must never be discarded. */
    long countBySeatIdInAndTicketIdIsNotNull(Collection<Long> seatIds);

    /** Rows of that schedule that already belong to a ticket. */
    long countByScheduleIdAndTicketIdIsNotNull(Long scheduleId);

    /**
     * Same as {@link #deleteReleasedWithoutTicketForSeats} for one schedule: {@code schedule_id} is
     * {@code ON DELETE RESTRICT} too, so a released hold would otherwise make a schedule that nobody
     * booked undeletable.
     *
     * @return how many rows were deleted
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from SeatReservation r
             where r.scheduleId = :scheduleId
               and r.status = :released
               and r.ticketId is null
            """)
    int deleteReleasedWithoutTicketForSchedule(@Param("scheduleId") Long scheduleId,
                                               @Param("released") ReservationStatus released);

    /**
     * Deletes the module's own finished history for those seats: RELEASED rows that were never paid.
     * Regenerating a seat grid deletes the {@code seats} rows these reservations point at, and
     * {@code seat_reservations.seat_id} is {@code ON DELETE RESTRICT}, so without this the first
     * released hold would block every later grid change.
     *
     * @return how many rows were deleted
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from SeatReservation r
             where r.seatId in :seatIds
               and r.status = :released
               and r.ticketId is null
            """)
    int deleteReleasedWithoutTicketForSeats(@Param("seatIds") Collection<Long> seatIds,
                                           @Param("released") ReservationStatus released);

    /**
     * Flips every HELD row whose {@code expiresAt} has passed to RELEASED (the scheduled sweeper).
     *
     * @return how many holds were released
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SeatReservation r
               set r.status = :released, r.expiresAt = null
             where r.status = :held
               and r.expiresAt is not null
               and r.expiresAt <= :now
            """)
    int releaseExpiredHolds(@Param("held") ReservationStatus held,
                            @Param("released") ReservationStatus released,
                            @Param("now") OffsetDateTime now);

    /**
     * Same as {@link #releaseExpiredHolds} but limited to the seats a hold request is about. It runs
     * inside the hold transaction, because the partial unique index does not know about expiry: an
     * expired HELD row would otherwise block the re-booking of its seat.
     *
     * @return how many holds were released
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SeatReservation r
               set r.status = :released, r.expiresAt = null
             where r.status = :held
               and r.scheduleId = :scheduleId
               and r.seatId in :seatIds
               and r.expiresAt is not null
               and r.expiresAt <= :now
            """)
    int releaseExpiredHoldsForSeats(@Param("scheduleId") Long scheduleId,
                                    @Param("seatIds") Collection<Long> seatIds,
                                    @Param("held") ReservationStatus held,
                                    @Param("released") ReservationStatus released,
                                    @Param("now") OffsetDateTime now);
}

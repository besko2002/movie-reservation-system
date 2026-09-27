package com.example.moviereservation.seatreservation;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.ResourceNotFoundException;
import com.example.moviereservation.movies.ScheduleSeatCategory;
import com.example.moviereservation.movies.ScheduleService;
import com.example.moviereservation.movies.SchedulePricesDto;
import com.example.moviereservation.theaters.SeatDto;
import com.example.moviereservation.theaters.TheaterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public API of the seat reservation module: the seat map of a schedule, timed seat holds, their
 * release, and the expiry of stale holds.
 *
 * <h2>Why double booking is impossible</h2>
 * <ol>
 *   <li>The database owns the rule: the partial unique index
 *       {@code ux_active_seat_per_schedule ON seat_reservations(schedule_id, seat_id)
 *       WHERE status IN ('HELD','CONFIRMED')} allows at most one active row per seat and schedule,
 *       while leaving RELEASED rows outside the index so a freed seat can be booked again.</li>
 *   <li>{@link #holdSeats(Long, HoldSeatsRequest)} still runs a pre-check SELECT, but only to
 *       produce a friendly 409 that names the taken seats. It is <strong>not</strong> the
 *       guarantee: two transactions can pass it at the same time.</li>
 *   <li>The insert of all requested seats happens in one transaction and is flushed. Whoever loses
 *       the race gets a {@link DataIntegrityViolationException} from the index, which is translated
 *       into a {@link ConflictException} (409). Because the whole method is one transaction, the
 *       loser's partially inserted rows are rolled back: a hold is always all-or-nothing.</li>
 * </ol>
 *
 * <h2>Expiry</h2>
 * A HELD row stops occupying its seat the moment {@code expiresAt} passes — lazily, in
 * {@link SeatReservation#isActiveAt(OffsetDateTime)}, so the seat map is correct even between two
 * runs of the sweeper. Because the unique index cannot know about expiry, every hold first releases
 * the expired holds of the seats it wants ({@code releaseExpiredHoldsForSeats}), and
 * {@link #releaseExpiredHolds()} does the same globally on a schedule.
 *
 * <h2>Seats that belong to a ticket</h2>
 * A hold gets its {@code ticketId} stamped on it the moment a checkout is opened for it
 * ({@link #attachHeldSeatsToTicket(Long, Long, Collection, Long, OffsetDateTime)}) while it is still
 * HELD — not only when the payment lands. That binding is what makes a ticket pay for a fixed set of
 * seats: only the attached rows are confirmed ({@link #confirmSeatsOfTicket(Long)}), so extra seats
 * the buyer happens to hold cannot ride along on that payment, and an attached hold cannot be
 * released behind the ticket's back ({@link #releaseOwnHold(Long, Long, boolean)} answers 409 for
 * it).
 *
 * <p>Other modules must depend on this service, never on {@code SeatReservationRepository}. Phase 7
 * uses {@link #getHeldSeatsForUser(Long, Long)},
 * {@link #attachHeldSeatsToTicket(Long, Long, Collection, Long, OffsetDateTime)},
 * {@link #extendHeldSeatsForUser(Long, Long, OffsetDateTime)},
 * {@link #confirmSeatsOfTicket(Long)}, {@link #releaseReservations(Collection)},
 * {@link #releaseSeatsOfTicket(Long)}, {@link #releaseHeldSeatsForUser(Long, Long)},
 * {@link #totalPriceOf(Collection)}, {@link #listReservationsOfTicket(Long)} and
 * {@link #listLiveHeldSeatsOfTicket(Long)}.
 */
@Service
public class SeatReservationService {

    private static final Logger log = LoggerFactory.getLogger(SeatReservationService.class);

    /** The two statuses covered by the partial unique index. */
    private static final Set<ReservationStatus> ACTIVE_STATUSES =
            EnumSet.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED);

    private final SeatReservationRepository reservationRepository;
    private final ScheduleService scheduleService;
    private final TheaterService theaterService;

    private final int holdMinutes;
    private final int maxSeatsPerRequest;

    public SeatReservationService(SeatReservationRepository reservationRepository,
                                  ScheduleService scheduleService,
                                  TheaterService theaterService,
                                  @Value("${app.reservation.hold-minutes}") int holdMinutes,
                                  @Value("${app.reservation.max-seats}") int maxSeatsPerRequest) {
        this.reservationRepository = reservationRepository;
        this.scheduleService = scheduleService;
        this.theaterService = theaterService;
        this.holdMinutes = holdMinutes;
        this.maxSeatsPerRequest = maxSeatsPerRequest;
    }

    /** @return how many minutes a fresh hold is valid */
    public int getHoldMinutes() {
        return holdMinutes;
    }

    /** @return how many seats one request may hold */
    public int getMaxSeatsPerRequest() {
        return maxSeatsPerRequest;
    }

    // --- seat map ------------------------------------------------------------------------------

    /**
     * Seat map of a schedule: every seat of its theater with the price this schedule charges and
     * the seat's current status. Expired holds are reported as AVAILABLE even when the sweeper has
     * not released them yet.
     *
     * @param scheduleId schedule to draw the map for
     * @return the seat map, ordered by row label then seat number
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public SeatMapDto getSeatMap(Long scheduleId) {
        requireSchedule(scheduleId);
        Long theaterId = scheduleService.getTheaterId(scheduleId);
        SchedulePricesDto prices = scheduleService.getPrices(scheduleId);
        OffsetDateTime now = OffsetDateTime.now();

        Map<Long, SeatStatus> statuses = new LinkedHashMap<>();
        for (SeatReservation reservation : activeReservationsOfSchedule(scheduleId, now)) {
            statuses.put(reservation.getSeatId(),
                    reservation.getStatus() == ReservationStatus.CONFIRMED ? SeatStatus.BOOKED : SeatStatus.HELD);
        }

        List<SeatMapSeatDto> seats = theaterService.listSeats(theaterId).stream()
                .map(seat -> new SeatMapSeatDto(
                        seat.id(),
                        seat.rowLabel(),
                        seat.seatNumber(),
                        seat.type(),
                        priceOf(prices, seat),
                        statuses.getOrDefault(seat.id(), SeatStatus.AVAILABLE)))
                .toList();
        return new SeatMapDto(scheduleId, theaterId, scheduleService.getStartTime(scheduleId), seats);
    }

    // --- holding -------------------------------------------------------------------------------

    /**
     * Holds all requested seats for one user, or none of them.
     *
     * @param userId  owner of the hold
     * @param request schedule and seats to hold
     * @return the created hold with its total price and expiry
     * @throws ResourceNotFoundException when no schedule has that id
     * @throws BadRequestException       when the seat list is empty, contains duplicates, exceeds
     *                                   {@code app.reservation.max-seats}, contains a seat that is
     *                                   not in the schedule's theater, or the showtime has started
     * @throws ConflictException         when at least one seat is already held or booked — either
     *                                   detected by the pre-check, or by the partial unique index
     *                                   when two requests race
     */
    @Transactional
    public SeatHoldDto holdSeats(Long userId, HoldSeatsRequest request) {
        Long scheduleId = request.scheduleId();
        List<Long> seatIds = requireUsableSeatIds(request.seatIds());
        requireSchedule(scheduleId);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime startTime = scheduleService.getStartTime(scheduleId);
        if (!startTime.isAfter(now)) {
            throw new BadRequestException(
                    "Schedule %d already started at %s; its seats cannot be held anymore"
                            .formatted(scheduleId, format(startTime)));
        }

        Map<Long, SeatDto> seatsOfTheater = seatsOfSchedule(scheduleId);
        List<SeatDto> requestedSeats = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            SeatDto seat = seatsOfTheater.get(seatId);
            if (seat == null) {
                throw new BadRequestException(
                        "Seat %d does not belong to the theater of schedule %d".formatted(seatId, scheduleId));
            }
            requestedSeats.add(seat);
        }

        // An expired HELD row is free for business, but the partial unique index still counts it,
        // so it has to become RELEASED before the new rows are inserted.
        reservationRepository.releaseExpiredHoldsForSeats(
                scheduleId, seatIds, ReservationStatus.HELD, ReservationStatus.RELEASED, now);

        // Friendly 409 only; the index below is the actual arbiter.
        List<SeatReservation> taken = reservationRepository
                .findByScheduleIdAndSeatIdInAndStatusIn(scheduleId, seatIds, ACTIVE_STATUSES).stream()
                .filter(reservation -> reservation.isActiveAt(now))
                .toList();
        if (!taken.isEmpty()) {
            throw new ConflictException(takenMessage(scheduleId, taken, seatsOfTheater));
        }

        OffsetDateTime expiresAt = now.plusMinutes(holdMinutes);
        SchedulePricesDto prices = scheduleService.getPrices(scheduleId);
        List<SeatReservation> toInsert = requestedSeats.stream()
                .map(seat -> new SeatReservation(userId, scheduleId, seat.id(), priceOf(prices, seat), expiresAt))
                .toList();

        List<SeatReservation> saved;
        try {
            // saveAllAndFlush: the INSERTs (and therefore the unique index) must be evaluated here,
            // not at commit time, so the violation can be translated while this transaction still
            // rolls back cleanly as a whole.
            saved = reservationRepository.saveAllAndFlush(toInsert);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent hold of at least one of these seats. The whole
            // transaction is rolled back, so none of the requested seats end up held by this user.
            log.debug("Hold of seats {} in schedule {} lost the race", seatIds, scheduleId, ex);
            throw new ConflictException(
                    ("At least one of the seats %s of schedule %d was taken by someone else; "
                            + "nothing was held, please reload the seat map")
                            .formatted(labelsOf(seatIds, seatsOfTheater), scheduleId));
        }

        List<HeldSeatDto> heldSeats = new ArrayList<>(saved.size());
        for (int index = 0; index < saved.size(); index++) {
            SeatReservation reservation = saved.get(index);
            SeatDto seat = requestedSeats.get(index);
            heldSeats.add(new HeldSeatDto(reservation.getId(), seat.id(), seat.rowLabel(), seat.seatNumber(),
                    seat.type(), reservation.getPrice()));
        }
        BigDecimal total = money(heldSeats.stream()
                .map(HeldSeatDto::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        log.info("User {} held {} seat(s) of schedule {} until {}", userId, heldSeats.size(), scheduleId,
                format(expiresAt));
        return new SeatHoldDto(heldSeats.stream().map(HeldSeatDto::reservationId).toList(),
                scheduleId, heldSeats, total, expiresAt);
    }

    // --- reads ---------------------------------------------------------------------------------

    /**
     * The caller's own reservations that still occupy a seat (unexpired holds and confirmed seats).
     *
     * @param userId owner
     * @return active reservations, oldest first
     */
    @Transactional(readOnly = true)
    public List<SeatReservationDto> getActiveReservationsForUser(Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        return reservationRepository.findByUserIdAndStatusInOrderByIdAsc(userId, ACTIVE_STATUSES).stream()
                .filter(reservation -> reservation.isActiveAt(now))
                .map(SeatReservationDto::from)
                .toList();
    }

    /**
     * Unexpired HELD seats of one user inside one schedule — what phase 7 turns into a ticket.
     *
     * @param userId     owner
     * @param scheduleId schedule
     * @return the user's live holds, oldest first (empty when there are none)
     */
    @Transactional(readOnly = true)
    public List<SeatReservationDto> getHeldSeatsForUser(Long userId, Long scheduleId) {
        OffsetDateTime now = OffsetDateTime.now();
        return heldRowsForUser(userId, scheduleId, now).stream()
                .map(SeatReservationDto::from)
                .toList();
    }

    /**
     * @param ticketId ticket id
     * @return every reservation that belongs to that ticket, oldest first
     */
    @Transactional(readOnly = true)
    public List<SeatReservationDto> listReservationsOfTicket(Long ticketId) {
        if (ticketId == null) {
            return List.of();
        }
        return reservationRepository.findByTicketIdOrderByIdAsc(ticketId).stream()
                .map(SeatReservationDto::from)
                .toList();
    }

    /**
     * The rows of a ticket that <em>still occupy their seat as a hold</em>: attached to that ticket,
     * HELD and not expired.
     *
     * <p>Phase 7 compares this against {@link #listReservationsOfTicket(Long)} before it accepts a
     * payment. A difference means the ticket can no longer be given the seats it was priced for
     * (a row was released, swept away or confirmed elsewhere), and the payment is refunded instead of
     * kept.
     *
     * @param ticketId ticket id; {@code null} yields an empty list
     * @return the ticket's live holds, oldest first
     */
    @Transactional(readOnly = true)
    public List<SeatReservationDto> listLiveHeldSeatsOfTicket(Long ticketId) {
        if (ticketId == null) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now();
        return reservationRepository.findByTicketIdOrderByIdAsc(ticketId).stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
                .filter(reservation -> reservation.isActiveAt(now))
                .map(SeatReservationDto::from)
                .toList();
    }

    /**
     * Sum of the price snapshots of the given reservations, scaled like {@code NUMERIC(10,2)}.
     *
     * @param reservations reservations to add up
     * @return the total, {@code 0.00} for an empty collection
     */
    public BigDecimal totalPriceOf(Collection<SeatReservationDto> reservations) {
        return money(reservations.stream()
                .map(SeatReservationDto::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * How many reservations still occupy a seat of that schedule. Used by the schedule deletion
     * seam; expired holds do not count.
     *
     * @param scheduleId schedule id; {@code null} counts as zero
     * @return the number of active reservations
     */
    @Transactional(readOnly = true)
    public long countActiveReservationsForSchedule(Long scheduleId) {
        if (scheduleId == null) {
            return 0L;
        }
        return activeReservationsOfSchedule(scheduleId, OffsetDateTime.now()).size();
    }

    /**
     * How many seats of that schedule are paid for ({@code CONFIRMED}) — the "booked" number of the
     * phase 8 occupancy report. A confirmed seat never expires, so no expiry filter applies.
     *
     * @param scheduleId schedule id; {@code null} counts as zero
     * @return the number of confirmed seats
     */
    @Transactional(readOnly = true)
    public long countConfirmedForSchedule(Long scheduleId) {
        if (scheduleId == null) {
            return 0L;
        }
        return reservationRepository
                .findByScheduleIdAndStatusIn(scheduleId, EnumSet.of(ReservationStatus.CONFIRMED)).size();
    }

    /**
     * How many seats of that schedule are held right now: {@code HELD} rows whose {@code expiresAt}
     * has not passed. An expired hold occupies nothing (see the expiry rule above), so it is not
     * counted even before the sweeper flipped its row — which is what makes the occupancy report
     * agree with the seat map.
     *
     * @param scheduleId schedule id; {@code null} counts as zero
     * @return the number of live holds
     */
    @Transactional(readOnly = true)
    public long countLiveHeldForSchedule(Long scheduleId) {
        if (scheduleId == null) {
            return 0L;
        }
        OffsetDateTime now = OffsetDateTime.now();
        return reservationRepository
                .findByScheduleIdAndStatusIn(scheduleId, EnumSet.of(ReservationStatus.HELD)).stream()
                .filter(reservation -> reservation.isActiveAt(now))
                .count();
    }

    /**
     * How many reservations still occupy one of those seats, in any schedule. Used by the seat
     * regeneration seam; expired holds do not count.
     *
     * @param seatIds seats to check; {@code null} or empty counts as zero
     * @return the number of active reservations
     */
    @Transactional(readOnly = true)
    public long countActiveReservationsForSeats(Collection<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return 0L;
        }
        OffsetDateTime now = OffsetDateTime.now();
        return reservationRepository.findBySeatIdInAndStatusIn(seatIds, ACTIVE_STATUSES).stream()
                .filter(reservation -> reservation.isActiveAt(now))
                .count();
    }

    /**
     * How many reservations of that schedule already belong to a ticket.
     *
     * @param scheduleId schedule id; {@code null} counts as zero
     * @return the number of ticketed reservations
     */
    @Transactional(readOnly = true)
    public long countTicketedReservationsForSchedule(Long scheduleId) {
        return scheduleId == null ? 0L : reservationRepository.countByScheduleIdAndTicketIdIsNotNull(scheduleId);
    }

    /**
     * Drops this module's finished, never-paid history for a schedule that is about to be deleted
     * ({@code seat_reservations.schedule_id} is {@code ON DELETE RESTRICT}).
     *
     * @param scheduleId schedule whose released reservations are discarded
     * @return how many rows were deleted
     */
    @Transactional
    public int discardReleasedReservationsForSchedule(Long scheduleId) {
        if (scheduleId == null) {
            return 0;
        }
        int deleted = reservationRepository.deleteReleasedWithoutTicketForSchedule(
                scheduleId, ReservationStatus.RELEASED);
        if (deleted > 0) {
            log.info("Discarded {} released reservation(s) of schedule {} before it is deleted", deleted, scheduleId);
        }
        return deleted;
    }

    /**
     * How many reservations of those seats already belong to a ticket. Their history must survive,
     * so the seat grid they point at may not be regenerated.
     *
     * @param seatIds seats to check; {@code null} or empty counts as zero
     * @return the number of ticketed reservations
     */
    @Transactional(readOnly = true)
    public long countTicketedReservationsForSeats(Collection<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return 0L;
        }
        return reservationRepository.countBySeatIdInAndTicketIdIsNotNull(seatIds);
    }

    /**
     * Drops this module's finished, never-paid history for those seats, so a theater whose grid is
     * being regenerated can actually delete its {@code seats} rows ({@code seat_reservations.seat_id}
     * is {@code ON DELETE RESTRICT}).
     *
     * <p>Only called from the seat regeneration seam, and only after
     * {@link #countActiveReservationsForSeats(Collection)} and
     * {@link #countTicketedReservationsForSeats(Collection)} both returned zero: nothing a user or a
     * report still needs is thrown away.
     *
     * @param seatIds seats whose released reservations are discarded
     * @return how many rows were deleted
     */
    @Transactional
    public int discardReleasedReservationsForSeats(Collection<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return 0;
        }
        int deleted = reservationRepository.deleteReleasedWithoutTicketForSeats(seatIds, ReservationStatus.RELEASED);
        if (deleted > 0) {
            log.info("Discarded {} released reservation(s) of {} seat(s) before their grid is regenerated",
                    deleted, seatIds.size());
        }
        return deleted;
    }

    // --- releasing -----------------------------------------------------------------------------

    /**
     * Releases one hold on behalf of a caller.
     *
     * <p>Authorisation decision: a reservation that belongs to somebody else is answered with
     * <strong>403 Forbidden</strong> (not 404). Reservation ids are dense and guessable, but they
     * carry no data on their own, and a clear 403 is easier to act on than a lie; admins may
     * release any hold.
     *
     * @param reservationId reservation to release
     * @param requesterId   id of the authenticated caller
     * @param requesterIsAdmin whether the caller has the ADMIN role
     * @throws ResourceNotFoundException when no reservation has that id
     * @throws AccessDeniedException     when the reservation belongs to another user and the caller
     *                                   is not an ADMIN
     * @throws ConflictException         when the reservation is already CONFIRMED (paid) — phase 7
     *                                   owns cancellation and refunds — or already RELEASED
     */
    @Transactional
    public void releaseOwnHold(Long reservationId, Long requesterId, boolean requesterIsAdmin) {
        SeatReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("SeatReservation", reservationId));
        if (!requesterIsAdmin && !reservation.getUserId().equals(requesterId)) {
            throw new AccessDeniedException(
                    "Reservation %d belongs to another user".formatted(reservationId));
        }
        switch (reservation.getStatus()) {
            case CONFIRMED -> throw new ConflictException(
                    ("Reservation %d is already paid (CONFIRMED); cancel the ticket instead of releasing the seat")
                            .formatted(reservationId));
            case RELEASED -> throw new ConflictException(
                    "Reservation %d is already released".formatted(reservationId));
            case HELD -> {
                if (reservation.getTicketId() != null) {
                    // The hold is priced into a pending checkout; freeing it alone would charge the
                    // buyer for a seat they no longer get.
                    throw new ConflictException(
                            ("Reservation %d belongs to pending ticket %d; cancel the ticket with "
                                    + "DELETE /api/tickets/%d instead").formatted(
                                    reservationId, reservation.getTicketId(), reservation.getTicketId()));
                }
                reservation.release();
                reservationRepository.flush();
                log.info("Released reservation id={} (seat {} of schedule {}) by user {}",
                        reservationId, reservation.getSeatId(), reservation.getScheduleId(), requesterId);
            }
        }
    }

    /**
     * Releases the given reservations whatever their state, as long as they are not paid. Phase 7
     * uses it when a checkout session is abandoned.
     *
     * @param reservationIds reservations to release
     * @return how many rows changed
     */
    @Transactional
    public int releaseReservations(Collection<Long> reservationIds) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return 0;
        }
        int released = 0;
        for (SeatReservation reservation : reservationRepository.findAllById(reservationIds)) {
            if (reservation.getStatus() == ReservationStatus.HELD) {
                reservation.release();
                released++;
            }
        }
        reservationRepository.flush();
        return released;
    }

    /**
     * Releases every seat of a ticket (phase 7: payment failed, cancelled or refunded).
     *
     * @param ticketId ticket whose seats are freed
     * @return how many rows changed
     */
    @Transactional
    public int releaseSeatsOfTicket(Long ticketId) {
        if (ticketId == null) {
            return 0;
        }
        int released = 0;
        for (SeatReservation reservation : reservationRepository.findByTicketIdOrderByIdAsc(ticketId)) {
            if (reservation.getStatus() != ReservationStatus.RELEASED) {
                reservation.release();
                released++;
            }
        }
        reservationRepository.flush();
        return released;
    }

    /**
     * Releases every live hold a user has in a schedule (used when a checkout is replaced).
     *
     * @param userId     owner
     * @param scheduleId schedule
     * @return how many rows changed
     */
    @Transactional
    public int releaseHeldSeatsForUser(Long userId, Long scheduleId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<SeatReservation> held = heldRowsForUser(userId, scheduleId, now);
        held.forEach(SeatReservation::release);
        reservationRepository.flush();
        return held.size();
    }

    // --- attaching to a ticket (phase 7 entry point) -------------------------------------------

    /**
     * Binds exactly the given live holds of one user to a ticket and pushes their expiry to
     * {@code newExpiresAt}, in the caller's transaction.
     *
     * <p>This is the checkout half of "a ticket pays for a fixed set of seats". Without it the
     * payment would confirm whatever the buyer happens to hold at the time the webhook arrives, and
     * two exploits follow from that: holding one more seat after checking out would get it for free,
     * and releasing a held seat after checking out would charge the full price for fewer seats.
     * Because the rows are stamped here, {@link #confirmSeatsOfTicket(Long)} can confirm the priced
     * rows and nothing else, and {@link #releaseOwnHold(Long, Long, boolean)} refuses to free one of
     * them.
     *
     * <p>The expiry extension is part of the same step on purpose: the seats of a ticket and its
     * payment page must die at the same instant (see {@code TicketCheckoutWindow}), and doing both in
     * one call means a hold can never be attached without its window being aligned.
     *
     * <p>All or nothing: every requested row is validated before the first one is changed, so a
     * rejected request leaves no half-attached ticket. The caller's transaction rolling back (a
     * payment provider that is not configured, for example) equally leaves no stamped row behind.
     *
     * @param userId         buyer the holds must belong to
     * @param scheduleId     schedule the holds must belong to
     * @param reservationIds the reservations to attach — exactly the rows the ticket was priced from
     * @param ticketId       ticket the holds now belong to
     * @param newExpiresAt   the instant those holds should expire at; {@code null} keeps their expiry
     * @return the attached reservations, oldest first
     * @throws BadRequestException when no ticket id or no reservation was given
     * @throws ConflictException   when one of the given rows is not a live HELD row of that user in
     *                             that schedule, or already belongs to another ticket
     */
    @Transactional
    public List<SeatReservationDto> attachHeldSeatsToTicket(Long userId,
                                                           Long scheduleId,
                                                           Collection<Long> reservationIds,
                                                           Long ticketId,
                                                           OffsetDateTime newExpiresAt) {
        if (ticketId == null) {
            throw new BadRequestException("A ticket id is required to attach seat holds to");
        }
        if (reservationIds == null || reservationIds.isEmpty()) {
            throw new ConflictException(
                    "Ticket %d cannot be checked out without a seat hold".formatted(ticketId));
        }
        Set<Long> wanted = new LinkedHashSet<>(reservationIds);
        List<SeatReservation> rows = reservationRepository.findAllById(wanted).stream()
                .sorted(Comparator.comparing(SeatReservation::getId))
                .toList();
        if (rows.size() != wanted.size()) {
            throw new ConflictException(
                    ("At least one of the seat holds %s does not exist anymore; nothing was attached to ticket %d, "
                            + "please reload the seat map").formatted(wanted, ticketId));
        }

        OffsetDateTime now = OffsetDateTime.now();
        for (SeatReservation reservation : rows) {
            requireAttachable(reservation, userId, scheduleId, ticketId, now);
        }
        for (SeatReservation reservation : rows) {
            reservation.attachToTicket(ticketId);
            reservation.extendTo(newExpiresAt);
        }
        reservationRepository.flush();
        log.info("Attached {} hold(s) of user {} in schedule {} to ticket {} (payable until {})",
                rows.size(), userId, scheduleId, ticketId,
                newExpiresAt == null ? "their original expiry" : format(newExpiresAt));
        return rows.stream().map(SeatReservationDto::from).toList();
    }

    private void requireAttachable(SeatReservation reservation, Long userId, Long scheduleId, Long ticketId,
                                   OffsetDateTime now) {
        if (!reservation.getUserId().equals(userId) || !reservation.getScheduleId().equals(scheduleId)) {
            throw new ConflictException(
                    ("Reservation %d is not a seat hold of user %d in schedule %d and cannot be paid with ticket %d")
                            .formatted(reservation.getId(), userId, scheduleId, ticketId));
        }
        if (reservation.getStatus() != ReservationStatus.HELD || !reservation.isActiveAt(now)) {
            throw new ConflictException(
                    ("Reservation %d is not a live seat hold anymore (%s); nothing was attached to ticket %d, "
                            + "please hold the seats again").formatted(
                            reservation.getId(), reservation.getStatus(), ticketId));
        }
        if (reservation.getTicketId() != null && !reservation.getTicketId().equals(ticketId)) {
            throw new ConflictException(
                    ("Reservation %d is already being paid with ticket %d; cancel that ticket before checking "
                            + "out again").formatted(reservation.getId(), reservation.getTicketId()));
        }
    }

    // --- extending (phase 7 entry point) -------------------------------------------------------

    /**
     * Pushes the expiry of a user's live holds in one schedule to {@code newExpiresAt}.
     *
     * <p>Phase 7 calls this when it opens a checkout: the payment page lives longer than a plain
     * hold ({@code app.reservation.hold-minutes}), and a hold that expires while its Checkout
     * Session is still payable is money lost — the sweeper would free the seats, somebody else could
     * take them, and the first buyer would pay for nothing. Aligning both windows on the same
     * instant removes that gap.
     *
     * <p>Only the caller's own HELD rows of that schedule are touched, and only forwards in time: an
     * already longer hold, a CONFIRMED seat or another user's hold are left exactly as they are.
     *
     * @param userId       owner of the holds
     * @param scheduleId   schedule
     * @param newExpiresAt the instant the holds should now expire at
     * @return how many holds were extended
     */
    @Transactional
    public int extendHeldSeatsForUser(Long userId, Long scheduleId, OffsetDateTime newExpiresAt) {
        if (newExpiresAt == null) {
            return 0;
        }
        List<SeatReservation> held = heldRowsForUser(userId, scheduleId, OffsetDateTime.now());
        int extended = 0;
        for (SeatReservation reservation : held) {
            if (reservation.getExpiresAt() == null || newExpiresAt.isAfter(reservation.getExpiresAt())) {
                reservation.extendTo(newExpiresAt);
                extended++;
            }
        }
        reservationRepository.flush();
        if (extended > 0) {
            log.info("Extended {} hold(s) of user {} in schedule {} until {}",
                    extended, userId, scheduleId, format(newExpiresAt));
        }
        return extended;
    }

    // --- confirming (phase 7 entry point) ------------------------------------------------------

    /**
     * Flips the holds <strong>of one ticket</strong> to CONFIRMED when its payment lands. The seats
     * stay inside the partial unique index the whole time, so a confirmed seat can never be held by
     * somebody else.
     *
     * <p>Only rows carrying that ticket id are touched — deliberately not "every hold of the buyer in
     * that schedule". A buyer may well hold further seats in the same showtime; those were not part of
     * what the ticket was priced for, so they stay HELD and expire normally instead of being handed
     * over for free.
     *
     * @param ticketId ticket whose seats were paid
     * @return the confirmed reservations, oldest first
     * @throws ConflictException when the ticket has no attached row left, or not all of its attached
     *                          rows are still live holds — the caller must then refund instead of
     *                          confirming a part of the seats
     */
    @Transactional
    public List<SeatReservationDto> confirmSeatsOfTicket(Long ticketId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<SeatReservation> attached = reservationRepository.findByTicketIdOrderByIdAsc(ticketId);
        List<SeatReservation> live = attached.stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
                .filter(reservation -> reservation.isActiveAt(now))
                .toList();
        if (live.isEmpty() || live.size() != attached.size()) {
            throw new ConflictException(
                    ("Ticket %d cannot be confirmed: only %d of its %d seat(s) are still held")
                            .formatted(ticketId, live.size(), attached.size()));
        }
        live.forEach(reservation -> reservation.confirm(ticketId));
        reservationRepository.flush();
        log.info("Confirmed {} seat(s) of ticket {} (user {}, schedule {})",
                live.size(), ticketId, live.get(0).getUserId(), live.get(0).getScheduleId());
        return live.stream().map(SeatReservationDto::from).toList();
    }

    // --- expiry --------------------------------------------------------------------------------

    /**
     * Flips every HELD row whose {@code expiresAt} has passed to RELEASED. Called by the scheduled
     * sweeper; safe to call at any time and from anywhere.
     *
     * @return how many holds were released
     */
    @Transactional
    public int releaseExpiredHolds() {
        int released = reservationRepository.releaseExpiredHolds(
                ReservationStatus.HELD, ReservationStatus.RELEASED, OffsetDateTime.now());
        if (released > 0) {
            log.info("Expiry sweep released {} stale hold(s)", released);
        }
        return released;
    }

    // --- internals -----------------------------------------------------------------------------

    private List<SeatReservation> activeReservationsOfSchedule(Long scheduleId, OffsetDateTime now) {
        return reservationRepository.findByScheduleIdAndStatusIn(scheduleId, ACTIVE_STATUSES).stream()
                .filter(reservation -> reservation.isActiveAt(now))
                .toList();
    }

    private List<SeatReservation> heldRowsForUser(Long userId, Long scheduleId, OffsetDateTime now) {
        return reservationRepository
                .findByUserIdAndScheduleIdAndStatusInOrderByIdAsc(userId, scheduleId, ACTIVE_STATUSES).stream()
                .filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
                .filter(reservation -> reservation.isActiveAt(now))
                .toList();
    }

    /** @return seats of the schedule's theater, by seat id, in seat map order */
    private Map<Long, SeatDto> seatsOfSchedule(Long scheduleId) {
        Map<Long, SeatDto> seats = new LinkedHashMap<>();
        for (SeatDto seat : theaterService.listSeats(scheduleService.getTheaterId(scheduleId))) {
            seats.put(seat.id(), seat);
        }
        return seats;
    }

    private List<Long> requireUsableSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BadRequestException("seatIds must not be empty");
        }
        if (seatIds.stream().anyMatch(seatId -> seatId == null)) {
            throw new BadRequestException("seatIds must not contain null");
        }
        Set<Long> unique = new LinkedHashSet<>(seatIds);
        if (unique.size() != seatIds.size()) {
            throw new BadRequestException("seatIds must not contain duplicates");
        }
        if (seatIds.size() > maxSeatsPerRequest) {
            throw new BadRequestException(
                    "At most %d seat(s) can be held in one request, but %d were requested"
                            .formatted(maxSeatsPerRequest, seatIds.size()));
        }
        return List.copyOf(seatIds);
    }

    private void requireSchedule(Long scheduleId) {
        if (!scheduleService.scheduleExists(scheduleId)) {
            throw new ResourceNotFoundException("Schedule", scheduleId);
        }
    }

    private static BigDecimal priceOf(SchedulePricesDto prices, SeatDto seat) {
        return money(prices.priceFor(ScheduleSeatCategory.of(seat.type().name())));
    }

    private static String takenMessage(Long scheduleId, List<SeatReservation> taken, Map<Long, SeatDto> seats) {
        List<Long> seatIds = taken.stream().map(SeatReservation::getSeatId).sorted().toList();
        return "Seat(s) %s of schedule %d are already held or booked".formatted(labelsOf(seatIds, seats), scheduleId);
    }

    /** Human readable seat labels ("A1", "B3") plus the raw ids, so a client can match either. */
    private static String labelsOf(Collection<Long> seatIds, Map<Long, SeatDto> seats) {
        return seatIds.stream()
                .map(seatId -> {
                    SeatDto seat = seats.get(seatId);
                    return seat == null
                            ? String.valueOf(seatId)
                            : "%s%d (id %d)".formatted(seat.rowLabel(), seat.seatNumber(), seatId);
                })
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}

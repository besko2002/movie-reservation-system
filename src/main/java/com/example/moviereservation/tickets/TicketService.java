package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.ResourceNotFoundException;
import com.example.moviereservation.movies.ScheduleDto;
import com.example.moviereservation.movies.ScheduleService;
import com.example.moviereservation.seatreservation.SeatReservationDto;
import com.example.moviereservation.seatreservation.SeatReservationService;
import com.example.moviereservation.theaters.SeatDto;
import com.example.moviereservation.theaters.TheaterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public API of the tickets module: checkout, the webhook state machine, "my tickets" and
 * cancellation with refund.
 *
 * <h2>Checkout idempotency (documented decision: return the existing pending ticket)</h2>
 * A user who already has a {@code PENDING_PAYMENT} ticket for the same schedule and posts
 * {@code POST /api/tickets} again gets <strong>that same ticket and that same payment page</strong>
 * back, answered with {@code 200 OK} instead of {@code 201 Created}; no second ticket and no second
 * session are ever created. A double click, a retried request or a client that lost the first
 * response can therefore never produce two payable tickets for one set of seats — which matters
 * because the buyer could otherwise pay both.
 *
 * <p>Only the session id is stored in {@code tickets}, so the URL and the expiry of that existing
 * session are read back through {@link PaymentGateway#retrieveCheckoutSession(String)}; the amount
 * shown stays the amount the existing session was opened with, and the answer's
 * {@code totalPrice} is the ticket's stored total for exactly that reason.
 *
 * <p>Consequence to know about: to pay for a different set of seats, the pending ticket has to be
 * cancelled first ({@code DELETE /api/tickets/{id}}); the next checkout then opens a fresh session.
 * The 409 answer of that {@code DELETE} is the only way a client can get stuck, and it cannot: a
 * pending ticket is always cancellable.
 *
 * <h2>The payment window (why a buyer cannot pay for seats somebody else got)</h2>
 * A fresh seat hold lives {@code app.reservation.hold-minutes} (10 by default) and
 * {@code SeatReservationExpirySweeper} releases it afterwards, while a hosted payment page lives
 * much longer. Creating a ticket therefore pushes the buyer's holds out to
 * {@code now + app.ticket.checkout-minutes} ({@link TicketCheckoutWindow}) and opens the payment
 * page with that very same {@code expiresAt}: the seats and the page die together, so the window in
 * which the seats could be resold while the page is still payable does not exist. The repeat of an
 * idempotent checkout does not extend anything — it hands back the original page, which still
 * expires with the original holds.
 *
 * <p>Should a payment nevertheless arrive with the seats gone, the ticket is <em>not</em> marked
 * PAID: the charge is refunded in full and the ticket becomes REFUNDED (see
 * {@code refundPaymentWithoutSeats}). The system never keeps money it cannot deliver seats for.
 *
 * <h2>Webhook idempotency</h2>
 * Every transition is guarded by the current status ({@code PENDING_PAYMENT -> PAID} and so on).
 * A redelivered event therefore finds the ticket already in its target state and does nothing,
 * which is exactly what Stripe's at-least-once delivery needs. No separate "processed events"
 * table is required because each event type has one target state and that state is idempotent.
 *
 * <h2>Module boundaries</h2>
 * Seats are read and changed only through {@link SeatReservationService}, schedules only through
 * {@link ScheduleService}, seat labels only through {@link TheaterService}. No other module's
 * repository or entity is touched.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final SeatReservationService seatReservationService;
    private final ScheduleService scheduleService;
    private final TheaterService theaterService;
    private final PaymentGateway paymentGateway;
    private final TicketCheckoutWindow checkoutWindow;

    private final String currency;
    private final int cancellationCutoffHours;

    public TicketService(TicketRepository ticketRepository,
                         SeatReservationService seatReservationService,
                         ScheduleService scheduleService,
                         TheaterService theaterService,
                         PaymentGateway paymentGateway,
                         TicketCheckoutWindow checkoutWindow,
                         @Value("${app.stripe.currency}") String currency,
                         @Value("${app.ticket.cancellation-cutoff-hours}") int cancellationCutoffHours) {
        this.ticketRepository = ticketRepository;
        this.seatReservationService = seatReservationService;
        this.scheduleService = scheduleService;
        this.theaterService = theaterService;
        this.paymentGateway = paymentGateway;
        this.checkoutWindow = checkoutWindow;
        this.currency = currency.toLowerCase();
        this.cancellationCutoffHours = cancellationCutoffHours;
    }

    /** @return how many hours before the showtime a paid ticket can still be cancelled */
    public int getCancellationCutoffHours() {
        return cancellationCutoffHours;
    }

    // --- checkout ------------------------------------------------------------------------------

    /**
     * Turns the caller's live seat holds in one schedule into a ticket plus a hosted payment page.
     *
     * <p>The seats are the ones the caller currently holds — a client cannot pay for seats it does
     * not hold. The seats stay {@code HELD}: they only become {@code CONFIRMED} when the payment
     * webhook arrives. Creating a new ticket extends those holds to
     * {@code now + app.ticket.checkout-minutes}, the instant the payment page also expires at, so
     * the seats cannot be swept away under a buyer who is still paying.
     *
     * <p>Idempotent per (user, schedule): while a {@code PENDING_PAYMENT} ticket exists for that
     * pair, the very same ticket and payment page come back and
     * {@link TicketCheckoutResult#created()} is {@code false}, so the endpoint answers 200 instead
     * of 201.
     *
     * @param userId  buyer
     * @param request which schedule to check out
     * @return the created (or already existing) ticket and where to pay
     * @throws ResourceNotFoundException when no schedule has that id
     * @throws ConflictException         when the caller holds no seat in that schedule
     * @throws com.example.moviereservation.common.ServiceUnavailableException when no payment
     *                                   provider is configured or it cannot be reached
     */
    @Transactional
    public TicketCheckoutResult checkout(Long userId, CreateTicketRequest request) {
        Long scheduleId = request.scheduleId();
        if (!scheduleService.scheduleExists(scheduleId)) {
            throw new ResourceNotFoundException("Schedule", scheduleId);
        }
        List<SeatReservationDto> held = seatReservationService.getHeldSeatsForUser(userId, scheduleId);
        if (held.isEmpty()) {
            throw new ConflictException(
                    ("You have no live seat hold in schedule %d; hold seats with POST /api/seat-reservations "
                            + "before checking out").formatted(scheduleId));
        }

        // Idempotency: one pending checkout per (user, schedule) - hand the existing one back.
        Optional<Ticket> pending = ticketRepository.findFirstByUserIdAndScheduleIdAndStatusOrderByIdDesc(
                userId, scheduleId, TicketStatus.PENDING_PAYMENT);
        if (pending.isPresent()) {
            // Holds that are not attached to the pending ticket would not be covered by its payment.
            boolean extraHolds = held.stream().anyMatch(reservation -> reservation.ticketId() == null);
            if (extraHolds) {
                throw new ConflictException(
                        ("A checkout is already pending for schedule %d (ticket %d) and does not cover the seats you "
                                + "held since; pay or cancel ticket %d first")
                                .formatted(scheduleId, pending.get().getId(), pending.get().getId()));
            }
            return new TicketCheckoutResult(existingCheckout(pending.get()), false);
        }

        BigDecimal total = seatReservationService.totalPriceOf(held);
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket(userId, scheduleId, total, currency));

        // Bind exactly the priced holds to this ticket and align their expiry with the payment page.
        OffsetDateTime expiresAt = checkoutWindow.expiryFrom(OffsetDateTime.now());
        seatReservationService.attachHeldSeatsToTicket(userId, scheduleId,
                held.stream().map(SeatReservationDto::id).toList(), ticket.getId(), expiresAt);

        ScheduleDto schedule = scheduleService.getScheduleById(scheduleId);
        Map<Long, SeatDto> seats = seatsOfSchedule(scheduleId);
        PaymentSessionDto session = paymentGateway.createCheckoutSession(new CheckoutSessionRequest(
                ticket.getId(), userId, scheduleId, schedule.movie().title(), total,
                lineItems(held, seats, schedule), expiresAt));

        ticket.setStripeSessionId(session.sessionId());
        ticketRepository.flush();
        log.info("Created ticket {} for user {} ({} seat(s) of schedule {}, total {} {}, payable until {})",
                ticket.getId(), userId, held.size(), scheduleId, total, currency, format(expiresAt));
        return new TicketCheckoutResult(
                // expiresAt is the instant this server put on both the seat holds and the payment
                // page, not whatever the provider echoes back: the client is told exactly how long
                // its seats are safe.
                new TicketCheckoutDto(ticket.getId(), ticket.getStatus(), total, currency,
                        session.checkoutUrl(), expiresAt),
                true);
    }

    /**
     * Re-describes the payment page of a ticket that is already waiting for its payment. The
     * session's URL and expiry are not stored, so the gateway is asked for them again.
     */
    private TicketCheckoutDto existingCheckout(Ticket ticket) {
        log.info("Checkout repeated for schedule {}: returning the existing pending ticket {} of user {}",
                ticket.getScheduleId(), ticket.getId(), ticket.getUserId());
        PaymentSessionDto session = paymentGateway.retrieveCheckoutSession(ticket.getStripeSessionId());
        return new TicketCheckoutDto(ticket.getId(), ticket.getStatus(), ticket.getTotalPrice(),
                ticket.getCurrency(), session.checkoutUrl(), session.expiresAt());
    }

    // --- reads ---------------------------------------------------------------------------------

    /**
     * @param userId caller
     * @return the caller's tickets, newest first, each with its seats
     */
    @Transactional(readOnly = true)
    public List<TicketDto> listMyTickets(Long userId) {
        return ticketRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * @param ticketId         ticket to read
     * @param requesterId      authenticated caller
     * @param requesterIsAdmin whether the caller has the ADMIN role
     * @return the ticket with its seats
     * @throws ResourceNotFoundException when no ticket has that id
     * @throws AccessDeniedException     when the ticket belongs to another user and the caller is
     *                                   not an ADMIN (403, not a 404 disguise — same decision as
     *                                   the seat reservation module)
     */
    @Transactional(readOnly = true)
    public TicketDto getTicket(Long ticketId, Long requesterId, boolean requesterIsAdmin) {
        return toDto(requireOwnedTicket(ticketId, requesterId, requesterIsAdmin));
    }

    // --- cancellation --------------------------------------------------------------------------

    /**
     * Cancels a ticket.
     *
     * <ul>
     *   <li>{@code PENDING_PAYMENT} &rarr; CANCELLED, the caller's holds for that schedule are
     *       released.</li>
     *   <li>{@code PAID} &rarr; only until {@code app.ticket.cancellation-cutoff-hours} before the
     *       showtime: a full refund is requested through the {@link PaymentGateway}, the ticket
     *       becomes REFUNDED and its seats are released.</li>
     *   <li>anything else (CANCELLED, REFUNDED, EXPIRED) &rarr; 409.</li>
     * </ul>
     *
     * @param ticketId         ticket to cancel
     * @param requesterId      authenticated caller
     * @param requesterIsAdmin whether the caller has the ADMIN role
     * @throws ResourceNotFoundException when no ticket has that id
     * @throws AccessDeniedException     when the ticket belongs to another user and the caller is
     *                                   not an ADMIN
     * @throws ConflictException         when the ticket is already finished, or the cutoff passed
     */
    @Transactional
    public void cancelTicket(Long ticketId, Long requesterId, boolean requesterIsAdmin) {
        Ticket ticket = requireOwnedTicket(ticketId, requesterId, requesterIsAdmin);
        OffsetDateTime now = OffsetDateTime.now();

        switch (ticket.getStatus()) {
            case PENDING_PAYMENT -> {
                ticket.markCancelled(now);
                // Only the holds attached to this ticket go; other holds of the user stay.
                seatReservationService.releaseSeatsOfTicket(ticketId);
                ticketRepository.flush();
                log.info("Cancelled pending ticket {} of user {}", ticketId, ticket.getUserId());
            }
            case PAID -> {
                requireBeforeCutoff(ticket, now);
                if (ticket.getStripePaymentIntentId() == null) {
                    throw new ConflictException(
                            ("Ticket %d is paid but carries no payment reference yet; "
                                    + "please retry in a moment").formatted(ticketId));
                }
                RefundDto refund = paymentGateway.refund(ticket.getStripePaymentIntentId(), ticket.getTotalPrice());
                ticket.markRefunded(now);
                seatReservationService.releaseSeatsOfTicket(ticketId);
                ticketRepository.flush();
                log.info("Refunded ticket {} of user {} ({} {}, refund {})",
                        ticketId, ticket.getUserId(), ticket.getTotalPrice(), ticket.getCurrency(),
                        refund.refundId());
            }
            default -> throw new ConflictException(
                    "Ticket %d is already %s and cannot be cancelled".formatted(ticketId, ticket.getStatus()));
        }
    }

    private void requireBeforeCutoff(Ticket ticket, OffsetDateTime now) {
        OffsetDateTime start = scheduleService.getStartTime(ticket.getScheduleId());
        OffsetDateTime cutoff = start.minusHours(cancellationCutoffHours);
        if (!now.isBefore(cutoff)) {
            throw new ConflictException(
                    ("Ticket %d can no longer be cancelled: the cancellation cutoff of %d hour(s) before the "
                            + "showtime passed at %s (the showtime starts at %s)")
                            .formatted(ticket.getId(), cancellationCutoffHours, format(cutoff), format(start)));
        }
    }

    // --- webhook state machine -----------------------------------------------------------------

    /**
     * Applies one verified payment event. Safe to call repeatedly with the same event: each
     * transition only fires from its expected source status, so a Stripe redelivery is a no-op.
     *
     * @param event verified event
     * @return a short description of what happened, for the endpoint's log and answer
     */
    @Transactional
    public String applyPaymentEvent(PaymentEvent event) {
        return switch (event.type()) {
            case PaymentEvent.CHECKOUT_SESSION_COMPLETED -> applyCompleted(event);
            case PaymentEvent.CHECKOUT_SESSION_EXPIRED ->
                    applyTerminal(event, TicketStatus.EXPIRED, "session expired");
            case PaymentEvent.PAYMENT_INTENT_PAYMENT_FAILED ->
                    applyTerminal(event, TicketStatus.CANCELLED, "payment failed");
            case PaymentEvent.CHARGE_REFUNDED -> applyRefunded(event);
            default -> {
                log.debug("Ignoring unhandled payment event type {} ({})", event.type(), event.id());
                yield "ignored: unhandled event type " + event.type();
            }
        };
    }

    private String applyCompleted(PaymentEvent event) {
        Optional<Ticket> found = findTicketOf(event);
        if (found.isEmpty()) {
            return notFound(event);
        }
        Ticket ticket = found.get();
        if (ticket.getStatus() == TicketStatus.PAID) {
            // Redelivery of an event that was already applied.
            log.debug("Ticket {} is already PAID; event {} changes nothing", ticket.getId(), event.id());
            return "ignored: ticket %d is already PAID".formatted(ticket.getId());
        }
        if (!ticket.getStatus().isPending()) {
            log.warn("Payment succeeded for ticket {} which is {}; manual follow-up needed (event {})",
                    ticket.getId(), ticket.getStatus(), event.id());
            return "ignored: ticket %d is %s".formatted(ticket.getId(), ticket.getStatus());
        }

        // Are ALL the seats this ticket was priced for still held? Asked before anything is written,
        // so the ticket is never left PAID on a payment this server cannot fully deliver. Checking
        // instead of catching the ConflictException of confirmSeatsOfTicket is deliberate: an
        // exception crossing that inner @Transactional boundary would mark the whole transaction
        // rollback-only, and the compensating refund below could then not be committed at all.
        List<SeatReservationDto> attached = seatReservationService.listReservationsOfTicket(ticket.getId());
        List<SeatReservationDto> live = seatReservationService.listLiveHeldSeatsOfTicket(ticket.getId());
        if (attached.isEmpty() || live.size() != attached.size()) {
            return refundPaymentWithoutSeats(ticket, event);
        }

        ticket.markPaid(event.paymentIntentId(), OffsetDateTime.now());
        if (event.sessionId() != null) {
            ticket.setStripeSessionId(event.sessionId());
        }
        ticketRepository.flush();
        seatReservationService.confirmSeatsOfTicket(ticket.getId());
        log.info("Ticket {} is PAID and its seats are CONFIRMED (event {})", ticket.getId(), event.id());
        return "applied: ticket %d is PAID".formatted(ticket.getId());
    }

    /**
     * Safety net for money that arrives when the seats are gone — clock skew, an admin who released
     * the holds, a delivery that was stuck for hours. Normally impossible, because
     * {@link TicketCheckoutWindow} makes the seat holds live exactly as long as the payment page.
     *
     * <p><strong>The money is never kept.</strong> The ticket does not become PAID: a full refund is
     * requested and the ticket becomes REFUNDED in the same transaction, so it can never be left
     * PAID-without-seats. The answer is still 200 — the delivery was genuine and there is nothing
     * for Stripe to retry.
     *
     * <p>Two deliberate exceptions to that:
     * <ul>
     *   <li>The refund call is <em>not</em> caught. A
     *       {@link com.example.moviereservation.common.ServiceUnavailableException} (no provider
     *       configured, provider down) propagates, this transaction rolls back, the endpoint answers
     *       5xx and Stripe redelivers the event later — which is exactly the retry this case
     *       needs.</li>
     *   <li>An event without a payment intent id cannot be refunded at all. Marking the ticket PAID
     *       would be worse (a paid ticket with no seats), so the ticket is left untouched for a
     *       human, the incident is logged at ERROR and the answer names it. Still 200: a redelivery
     *       of the same event would not conjure up a payment intent either.</li>
     * </ul>
     */
    private String refundPaymentWithoutSeats(Ticket ticket, PaymentEvent event) {
        String paymentIntentId = event.paymentIntentId() != null
                ? event.paymentIntentId()
                : ticket.getStripePaymentIntentId();
        if (paymentIntentId == null) {
            log.error("Payment event {} completed ticket {} but its seat holds are gone AND the event carries no "
                            + "payment intent id: the charge cannot be refunded automatically, manual follow-up "
                            + "needed", event.id(), ticket.getId());
            return ("manual-follow-up: ticket %d was paid without seats and carries no payment intent id; it was "
                    + "left %s and has to be refunded by hand")
                    .formatted(ticket.getId(), ticket.getStatus());
        }

        RefundDto refund = paymentGateway.refund(paymentIntentId, ticket.getTotalPrice());
        ticket.setStripePaymentIntentId(paymentIntentId);
        if (event.sessionId() != null) {
            ticket.setStripeSessionId(event.sessionId());
        }
        ticket.markRefunded(OffsetDateTime.now());
        releaseEverythingOf(ticket);
        ticketRepository.flush();
        log.warn("Ticket {} was paid after its seat holds were gone; refunded {} {} in full (refund {}, event {})",
                ticket.getId(), ticket.getTotalPrice(), ticket.getCurrency(), refund.refundId(), event.id());
        return ("refunded: ticket %d was paid after its seat holds were gone, so the payment was refunded in full")
                .formatted(ticket.getId());
    }

    private String applyTerminal(PaymentEvent event, TicketStatus target, String reason) {
        Optional<Ticket> found = findTicketOf(event);
        if (found.isEmpty()) {
            return notFound(event);
        }
        Ticket ticket = found.get();
        if (!ticket.getStatus().isPending()) {
            log.debug("Ticket {} is {}; event {} ({}) changes nothing", ticket.getId(), ticket.getStatus(),
                    event.id(), event.type());
            return "ignored: ticket %d is %s".formatted(ticket.getId(), ticket.getStatus());
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (target == TicketStatus.EXPIRED) {
            ticket.markExpired(now);
        } else {
            ticket.markCancelled(now);
        }
        releaseEverythingOf(ticket);
        ticketRepository.flush();
        log.info("Ticket {} is {} ({}, event {})", ticket.getId(), target, reason, event.id());
        return "applied: ticket %d is %s".formatted(ticket.getId(), target);
    }

    private String applyRefunded(PaymentEvent event) {
        Optional<Ticket> found = findTicketOf(event);
        if (found.isEmpty()) {
            return notFound(event);
        }
        Ticket ticket = found.get();
        if (ticket.getStatus() == TicketStatus.REFUNDED) {
            log.debug("Ticket {} is already REFUNDED; event {} changes nothing", ticket.getId(), event.id());
            return "ignored: ticket %d is already REFUNDED".formatted(ticket.getId());
        }
        if (ticket.getStatus() != TicketStatus.PAID) {
            log.warn("Refund event {} for ticket {} which is {}", event.id(), ticket.getId(), ticket.getStatus());
            return "ignored: ticket %d is %s".formatted(ticket.getId(), ticket.getStatus());
        }
        ticket.markRefunded(OffsetDateTime.now());
        releaseEverythingOf(ticket);
        ticketRepository.flush();
        log.info("Ticket {} is REFUNDED and its seats are released (event {})", ticket.getId(), event.id());
        return "applied: ticket %d is REFUNDED".formatted(ticket.getId());
    }

    /** Frees the seats attached to this ticket; the buyer's other holds are not this ticket's. */
    private void releaseEverythingOf(Ticket ticket) {
        seatReservationService.releaseSeatsOfTicket(ticket.getId());
    }

    private String notFound(PaymentEvent event) {
        // Answering 2xx anyway: a delivery this server cannot match (another environment's data,
        // a ticket that was purged) must not make Stripe retry forever.
        log.warn("No ticket matches payment event {} ({}, session={}, paymentIntent={})",
                event.id(), event.type(), event.sessionId(), event.paymentIntentId());
        return "ignored: no ticket matches event " + event.id();
    }

    /** ticket id from the event, else the unique session id, else the payment intent. */
    private Optional<Ticket> findTicketOf(PaymentEvent event) {
        if (event.ticketId() != null) {
            Optional<Ticket> byId = ticketRepository.findById(event.ticketId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        if (event.sessionId() != null) {
            Optional<Ticket> bySession = ticketRepository.findByStripeSessionId(event.sessionId());
            if (bySession.isPresent()) {
                return bySession;
            }
        }
        if (event.paymentIntentId() != null) {
            return ticketRepository.findByStripePaymentIntentIdOrderByIdDesc(event.paymentIntentId())
                    .stream().findFirst();
        }
        return Optional.empty();
    }

    // --- internals -----------------------------------------------------------------------------

    private Ticket requireOwnedTicket(Long ticketId, Long requesterId, boolean requesterIsAdmin) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        if (!requesterIsAdmin && !ticket.isOwnedBy(requesterId)) {
            throw new AccessDeniedException("Ticket %d belongs to another user".formatted(ticketId));
        }
        return ticket;
    }

    /**
     * Package-private so {@link ReportService} can present the admin ticket list with exactly the
     * same shape (and the same seat resolution) as {@code /api/tickets/me}, instead of growing a
     * second, drifting mapping for admins.
     */
    TicketDto toDto(Ticket ticket) {
        ScheduleDto schedule = scheduleService.getScheduleById(ticket.getScheduleId());
        Map<Long, SeatDto> seats = seatsOfSchedule(ticket.getScheduleId());
        List<TicketSeatDto> ticketSeats = reservationsOf(ticket).stream()
                .map(reservation -> toSeatDto(reservation, seats.get(reservation.seatId())))
                .toList();
        return new TicketDto(
                ticket.getId(),
                ticket.getUserId(),
                ticket.getScheduleId(),
                schedule.movie().title(),
                schedule.startTime(),
                ticket.getStatus(),
                ticket.getTotalPrice(),
                ticket.getCurrency(),
                ticketSeats.size(),
                ticketSeats,
                ticket.getCreatedAt(),
                ticket.getPaidAt(),
                ticket.getCancelledAt());
    }

    /** The seats a ticket shows: the rows attached to it at checkout ({@code ticket_id}). */
    private List<SeatReservationDto> reservationsOf(Ticket ticket) {
        return seatReservationService.listReservationsOfTicket(ticket.getId());
    }

    private static TicketSeatDto toSeatDto(SeatReservationDto reservation, SeatDto seat) {
        return new TicketSeatDto(
                reservation.id(),
                reservation.seatId(),
                seat == null ? null : seat.rowLabel(),
                seat == null ? 0 : seat.seatNumber(),
                seat == null ? null : seat.type(),
                reservation.price(),
                reservation.status());
    }

    private List<CheckoutLineItem> lineItems(List<SeatReservationDto> held,
                                             Map<Long, SeatDto> seats,
                                             ScheduleDto schedule) {
        List<CheckoutLineItem> items = new ArrayList<>(held.size());
        for (SeatReservationDto reservation : held) {
            SeatDto seat = seats.get(reservation.seatId());
            String label = seat == null
                    ? "Seat #" + reservation.seatId()
                    : "Seat %s%d (%s)".formatted(seat.rowLabel(), seat.seatNumber(), seat.type());
            items.add(new CheckoutLineItem(
                    label,
                    "%s - %s, %s".formatted(schedule.movie().title(), schedule.theater().name(),
                            format(schedule.startTime())),
                    reservation.price()));
        }
        return items;
    }

    private Map<Long, SeatDto> seatsOfSchedule(Long scheduleId) {
        Map<Long, SeatDto> seats = new LinkedHashMap<>();
        for (SeatDto seat : theaterService.listSeats(scheduleService.getTheaterId(scheduleId))) {
            seats.put(seat.id(), seat);
        }
        return seats;
    }

    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}

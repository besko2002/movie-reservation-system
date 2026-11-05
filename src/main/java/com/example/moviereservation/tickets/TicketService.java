package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.PageResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
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
 * <p>Should a payment nevertheless arrive with the seats gone, or after the ticket was cancelled or
 * expired, the ticket is <em>not</em> marked PAID: the captured amount is refunded and the ticket
 * becomes REFUNDED (see {@code refundPaymentWithoutSeats}). The system never keeps money it cannot
 * deliver seats for. A cancelled pending ticket also asks the gateway to expire its hosted page,
 * so a late payment is normally prevented rather than merely compensated.
 *
 * <h2>Concurrency</h2>
 * Checkout takes a transaction-scoped PostgreSQL advisory lock keyed by (user, schedule) before it
 * checks for an existing pending ticket, so two simultaneous requests create one ticket and one
 * payment page. Ticket state transitions take a row lock: a webhook and a cancellation racing for
 * the same ticket are serialized, and the second one sees the first one's committed status.
 *
 * <h2>Webhook idempotency</h2>
 * Every transition is guarded by the current status ({@code PENDING_PAYMENT -> PAID} and so on).
 * A redelivered event therefore finds the ticket already in its target state and does nothing,
 * which is exactly what Stripe's at-least-once delivery needs. No separate "processed events"
 * table is required because each event type has one target state and that state is idempotent.
 * Refunds additionally use a stable per-ticket idempotency key, so a redelivery after a database
 * failure asks Stripe for the same refund instead of creating a second one.
 *
 * <h2>Module boundaries</h2>
 * Seats are read and changed only through {@link SeatReservationService}, schedules only through
 * {@link ScheduleService}, seat labels only through {@link TheaterService}. No other module's
 * repository or entity is touched.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final TicketRepository ticketRepository;
    private final TicketRefundRepository refundRepository;
    private final SeatReservationService seatReservationService;
    private final ScheduleService scheduleService;
    private final TheaterService theaterService;
    private final PaymentGateway paymentGateway;
    private final TicketCheckoutWindow checkoutWindow;

    private final String currency;
    private final int cancellationCutoffHours;

    public TicketService(TicketRepository ticketRepository,
                         TicketRefundRepository refundRepository,
                         SeatReservationService seatReservationService,
                         ScheduleService scheduleService,
                         TheaterService theaterService,
                         PaymentGateway paymentGateway,
                         TicketCheckoutWindow checkoutWindow,
                         @Value("${app.stripe.currency}") String currency,
                         @Value("${app.ticket.cancellation-cutoff-hours}") int cancellationCutoffHours) {
        this.ticketRepository = ticketRepository;
        this.refundRepository = refundRepository;
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
        OffsetDateTime startTime = scheduleService.getStartTime(scheduleId);
        if (!startTime.isAfter(OffsetDateTime.now())) {
            throw new ConflictException(
                    "Schedule %d already started at %s; tickets cannot be bought for it anymore"
                            .formatted(scheduleId, format(startTime)));
        }
        // Serializes the check-then-create below for this buyer and showtime. Without it, two
        // simultaneous requests can both see "no pending ticket" and create two payable sessions.
        ticketRepository.lockCheckoutPair(checkoutLockKey(userId, scheduleId));

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
    public PageResponse<TicketDto> listMyTickets(Long userId, Integer page, Integer size) {
        Page<Ticket> tickets = ticketRepository.findByUserId(userId, PageRequest.of(
                page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("id"))));
        return PageResponse.of(tickets, toDtos(tickets.getContent()));
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
        // Locked: a webhook for this ticket may be arriving at the same time. Whichever transaction
        // gets here second sees the status the first one committed, never a stale PENDING_PAYMENT.
        Ticket ticket = requireOwnedTicketForUpdate(ticketId, requesterId, requesterIsAdmin);
        OffsetDateTime now = OffsetDateTime.now();

        switch (ticket.getStatus()) {
            case PENDING_PAYMENT -> {
                ticket.markCancelled(now);
                // Only the holds attached to this ticket go; other holds of the user stay.
                seatReservationService.releaseSeatsOfTicket(ticketId);
                ticketRepository.flush();
                // The buyer may still have the hosted page open. Expiring it prevents a payment;
                // if the provider is unreachable, the webhook safety net refunds a late payment.
                expireCheckoutSessionQuietly(ticket);
                log.info("Cancelled pending ticket {} of user {}", ticketId, ticket.getUserId());
            }
            case PAID -> {
                requireBeforeCutoff(ticket, now);
                if (ticket.getStripePaymentIntentId() == null) {
                    throw new ConflictException(
                            ("Ticket %d is paid but carries no payment reference yet; "
                                    + "please retry in a moment").formatted(ticketId));
                }
                BigDecimal remaining = ticket.getTotalPrice().subtract(refundedSoFar(ticketId));
                RefundDto refund = remaining.signum() > 0
                        ? paymentGateway.refund(ticket.getStripePaymentIntentId(), remaining, refundKey(ticketId))
                        : null;
                recordRefund(ticket, remaining, now);
                ticket.markRefunded(now);
                seatReservationService.releaseSeatsOfTicket(ticketId);
                ticketRepository.flush();
                log.info("Refunded ticket {} of user {} ({} {}, refund {})",
                        ticketId, ticket.getUserId(), ticket.getTotalPrice(), ticket.getCurrency(),
                        refund == null ? "already fully refunded" : refund.refundId());
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
            case PaymentEvent.CHECKOUT_SESSION_COMPLETED,
                 PaymentEvent.CHECKOUT_SESSION_ASYNC_PAYMENT_SUCCEEDED -> applyCompleted(event);
            case PaymentEvent.CHECKOUT_SESSION_EXPIRED ->
                    applyTerminal(event, TicketStatus.EXPIRED, "session expired");
            case PaymentEvent.CHECKOUT_SESSION_ASYNC_PAYMENT_FAILED ->
                    applyTerminal(event, TicketStatus.CANCELLED, "payment failed");
            // A card decline can be retried on the same hosted Checkout page. Do not release its
            // seats until the session expires or a terminal async failure is reported.
            case PaymentEvent.PAYMENT_INTENT_PAYMENT_FAILED -> "ignored: payment attempt can be retried";
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
        if (ticket.getStatus() == TicketStatus.REFUNDED) {
            // The money was already given back; a late completed event must not refund it again.
            log.debug("Ticket {} is already REFUNDED; event {} changes nothing", ticket.getId(), event.id());
            return "ignored: ticket %d is already REFUNDED".formatted(ticket.getId());
        }
        if (ticket.getStatus().isFinal()) {
            // The buyer cancelled or the session expired, but a payment still landed. Most likely
            // the checkout page was open when the cancellation happened. The money cannot buy a
            // ticket, so it is refunded automatically instead of being kept or left for a human.
            if (!"paid".equalsIgnoreCase(event.paymentStatus())) {
                log.info("Ticket {} is {} and event {} is not paid; nothing to refund",
                        ticket.getId(), ticket.getStatus(), event.id());
                return "ignored: ticket %d is %s and the payment is not completed"
                        .formatted(ticket.getId(), ticket.getStatus());
            }
            log.warn("Payment succeeded for {} ticket {}; refunding it automatically (event {})",
                    ticket.getStatus(), ticket.getId(), event.id());
            return refundPaymentWithoutSeats(ticket, event,
                    "the ticket was already " + ticket.getStatus());
        }

        // checkout.session.completed can arrive for delayed methods while the session is still
        // unpaid; only Stripe's "paid" value means the money is actually there.
        if (!"paid".equalsIgnoreCase(event.paymentStatus())) {
            log.warn("Event {} reports payment_status={} for ticket {}; leaving it PENDING_PAYMENT",
                    event.id(), event.paymentStatus(), ticket.getId());
            return "ignored: payment not completed (payment_status=%s)".formatted(event.paymentStatus());
        }

        // Never let an event for one Checkout Session pay a different session's ticket. The ticket
        // id in metadata is a lookup hint, not proof that this session belongs to it.
        if (event.sessionId() != null
                && ticket.getStripeSessionId() != null
                && !event.sessionId().equals(ticket.getStripeSessionId())) {
            log.error("Event {} is for session {} but ticket {} was opened with session {}; ignoring it",
                    event.id(), event.sessionId(), ticket.getId(), ticket.getStripeSessionId());
            return "ignored: session %s does not match ticket %d"
                    .formatted(event.sessionId(), ticket.getId());
        }

        // The amount and currency must be exactly what this ticket was priced at. A mismatch means
        // the payment cannot buy this ticket, so a captured charge is refunded rather than kept.
        if (event.amountTotal() != null && event.currency() != null) {
            long expectedAmount = toMinorUnits(ticket.getTotalPrice(), ticket.getCurrency());
            if (event.amountTotal() != expectedAmount
                    || !event.currency().equalsIgnoreCase(ticket.getCurrency())) {
                log.error("Event {} paid {} {} but ticket {} costs {} {}; refunding the payment",
                        event.id(), event.amountTotal(), event.currency(),
                        ticket.getId(), ticket.getTotalPrice(), ticket.getCurrency());
                if (event.paymentIntentId() == null) {
                    return manualFollowUp(ticket, event, "amount/currency mismatch");
                }
                return refundPaymentWithoutSeats(ticket, event, "amount/currency mismatch",
                        fromMinorUnits(event.amountTotal(), event.currency()));
            }
        }

        // A paid ticket needs its payment intent: without it the buyer could never be refunded.
        if (event.paymentIntentId() == null) {
            return manualFollowUp(ticket, event, "no payment intent id");
        }

        // Are ALL the seats this ticket was priced for still held? Asked before anything is written,
        // so the ticket is never left PAID on a payment this server cannot fully deliver. Checking
        // instead of catching the ConflictException of confirmSeatsOfTicket is deliberate: an
        // exception crossing that inner @Transactional boundary would mark the whole transaction
        // rollback-only, and the compensating refund below could then not be committed at all.
        List<SeatReservationDto> attached = seatReservationService.listReservationsOfTicket(ticket.getId());
        List<SeatReservationDto> live = seatReservationService.listLiveHeldSeatsOfTicket(ticket.getId());
        if (attached.isEmpty() || live.size() != attached.size()) {
            return refundPaymentWithoutSeats(ticket, event, "the seat holds are gone");
        }

        ticket.markPaid(event.paymentIntentId(), OffsetDateTime.now());
        if (ticket.getStripeSessionId() == null && event.sessionId() != null) {
            // Only fill a missing reference; never overwrite the session this ticket was opened
            // with, because that is what binds the next event to the right ticket.
            ticket.setStripeSessionId(event.sessionId());
        }
        ticketRepository.flush();
        seatReservationService.confirmSeatsOfTicket(ticket.getId());
        log.info("Ticket {} is PAID and its seats are CONFIRMED (event {})", ticket.getId(), event.id());
        return "applied: ticket %d is PAID".formatted(ticket.getId());
    }

    /**
     * Safety net for money that arrives when the ticket cannot be delivered — the seats are gone,
     * the ticket was already cancelled or expired, or the amount/currency does not match. Normally
     * impossible, because {@link TicketCheckoutWindow} makes the seat holds live exactly as long as
     * the payment page and cancellation expires the page.
     *
     * <p><strong>The money is never kept.</strong> The ticket does not become PAID: a refund is
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
    private String refundPaymentWithoutSeats(Ticket ticket, PaymentEvent event, String reason) {
        return refundPaymentWithoutSeats(ticket, event, reason, null);
    }

    private String refundPaymentWithoutSeats(Ticket ticket, PaymentEvent event, String reason,
                                             BigDecimal amountOverride) {
        String paymentIntentId = event.paymentIntentId() != null
                ? event.paymentIntentId()
                : ticket.getStripePaymentIntentId();
        if (paymentIntentId == null) {
            return manualFollowUp(ticket, event, reason + " and no payment intent id");
        }
        BigDecimal refundAmount = amountOverride != null
                ? amountOverride
                : event.amountTotal() != null && event.currency() != null
                        ? fromMinorUnits(event.amountTotal(), event.currency())
                        : ticket.getTotalPrice();

        // The key is stable per ticket: if Stripe succeeded but this transaction rolled back, the
        // redelivered event asks for the same refund and Stripe returns the original one instead of
        // moving the money a second time.
        RefundDto refund = paymentGateway.refund(paymentIntentId, refundAmount,
                refundKey(ticket.getId()));
        ticket.setStripePaymentIntentId(paymentIntentId);
        if (ticket.getStripeSessionId() == null && event.sessionId() != null) {
            ticket.setStripeSessionId(event.sessionId());
        }
        OffsetDateTime now = OffsetDateTime.now();
        // Compensating refunds can occur before the normal PAID transition. Preserve the capture
        // timestamp for historical gross reporting when the amount/currency match this ticket.
        if (event.currency() != null && event.currency().equalsIgnoreCase(ticket.getCurrency())
                && refundAmount.compareTo(ticket.getTotalPrice()) == 0) {
            ticket.recordCapturedAt(now);
        }
        recordRefund(ticket, refundAmount, now);
        ticket.markRefunded(now);
        releaseEverythingOf(ticket);
        ticketRepository.flush();
        log.warn("Ticket {} cannot be delivered ({}); refunded {} {} in full (refund {}, event {})",
                ticket.getId(), reason, refundAmount, ticket.getCurrency(), refund.refundId(), event.id());
        return ("refunded: ticket %d cannot be delivered (%s), so the payment was refunded in full")
                .formatted(ticket.getId(), reason);
    }

    private String manualFollowUp(Ticket ticket, PaymentEvent event, String reason) {
        log.error("Payment event {} completed ticket {} but {}; the charge cannot be refunded automatically, "
                        + "manual follow-up needed (ticket left {})",
                event.id(), ticket.getId(), reason, ticket.getStatus());
        return ("manual-follow-up: ticket %d cannot be delivered (%s) and carries no payment intent id; "
                + "it was left %s and has to be refunded by hand")
                .formatted(ticket.getId(), reason, ticket.getStatus());
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
        if (ticket.getStatus() != TicketStatus.PAID && ticket.getStatus() != TicketStatus.REFUNDED) {
            log.warn("Refund event {} for ticket {} which is {}", event.id(), ticket.getId(), ticket.getStatus());
            return "ignored: ticket %d is %s".formatted(ticket.getId(), ticket.getStatus());
        }
        if (event.currency() != null && !event.currency().equalsIgnoreCase(ticket.getCurrency())) {
            log.error("Refund event {} currency {} differs from ticket {} currency {}",
                    event.id(), event.currency(), ticket.getId(), ticket.getCurrency());
            return "ignored: refund currency mismatch";
        }
        BigDecimal cumulative = event.amountRefunded() == null
                ? ticket.getTotalPrice()
                : fromMinorUnits(event.amountRefunded(), ticket.getCurrency());
        BigDecimal delta = cumulative.subtract(refundedSoFar(ticket.getId()));
        if (delta.signum() <= 0) {
            return "ignored: refund already recorded for ticket %d".formatted(ticket.getId());
        }
        OffsetDateTime now = OffsetDateTime.now();
        recordRefund(ticket, delta, now);
        if (event.amount() != null && event.amountRefunded() != null
                && event.amountRefunded() < event.amount()) {
            log.info("Partial refund for ticket {} ({}/{} minor units); ticket stays PAID",
                    ticket.getId(), event.amountRefunded(), event.amount());
            return "applied: partial refund for ticket %d, still PAID".formatted(ticket.getId());
        }
        ticket.markRefunded(now);
        releaseEverythingOf(ticket);
        ticketRepository.flush();
        log.info("Ticket {} is REFUNDED and its seats are released (event {})", ticket.getId(), event.id());
        return "applied: ticket %d is REFUNDED".formatted(ticket.getId());
    }

    private BigDecimal refundedSoFar(Long ticketId) {
        BigDecimal amount = refundRepository.sumForTicket(ticketId);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private void recordRefund(Ticket ticket, BigDecimal amount, OffsetDateTime now) {
        if (amount.signum() > 0) {
            refundRepository.saveAndFlush(new TicketRefund(ticket.getId(), amount, now));
        }
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

    /**
     * ticket id from the event, else the unique session id, else the payment intent. Every lookup
     * takes a row lock, so a redelivery racing the first delivery waits for it and then sees the
     * resulting status instead of applying the same transition twice.
     */
    private Optional<Ticket> findTicketOf(PaymentEvent event) {
        if (event.ticketId() != null) {
            Optional<Ticket> byId = ticketRepository.findByIdForUpdate(event.ticketId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        if (event.sessionId() != null) {
            Optional<Ticket> bySession = ticketRepository.findByStripeSessionIdForUpdate(event.sessionId());
            if (bySession.isPresent()) {
                return bySession;
            }
        }
        if (event.paymentIntentId() != null) {
            return ticketRepository.findByStripePaymentIntentIdForUpdate(event.paymentIntentId())
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

    private Ticket requireOwnedTicketForUpdate(Long ticketId, Long requesterId, boolean requesterIsAdmin) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketId));
        if (!requesterIsAdmin && !ticket.isOwnedBy(requesterId)) {
            throw new AccessDeniedException("Ticket %d belongs to another user".formatted(ticketId));
        }
        return ticket;
    }

    /**
     * Stable transaction-scoped lock key for one buyer's checkout in one schedule. Collisions only
     * make two unrelated checkouts wait for each other; they never mix data.
     */
    private static long checkoutLockKey(Long userId, Long scheduleId) {
        return (userId << 32) ^ scheduleId;
    }

    /** Stable idempotency key for every refund attempt of one ticket. */
    private static String refundKey(Long ticketId) {
        return "ticket-%d-refund".formatted(ticketId);
    }

    private void expireCheckoutSessionQuietly(Ticket ticket) {
        if (ticket.getStripeSessionId() == null || ticket.getStripeSessionId().isBlank()) {
            return;
        }
        try {
            paymentGateway.expireCheckoutSession(ticket.getStripeSessionId());
        } catch (RuntimeException ex) {
            // The cancellation itself must still succeed. If the page cannot be expired and a
            // payment lands later, applyCompleted() refunds it automatically.
            log.warn("Ticket {} was cancelled but its Checkout Session {} could not be expired; "
                            + "a late payment will be refunded by the webhook",
                    ticket.getId(), ticket.getStripeSessionId(), ex);
        }
    }

    /** Converts a major-unit amount to Stripe's minor unit for that ticket's currency. */
    private static long toMinorUnits(BigDecimal amount, String currencyCode) {
        return amount.setScale(fractionDigitsOf(currencyCode), RoundingMode.HALF_UP)
                .movePointRight(fractionDigitsOf(currencyCode))
                .longValueExact();
    }

    /** Converts a Stripe minor-unit amount back to the major unit used by tickets. */
    private static BigDecimal fromMinorUnits(long amount, String currencyCode) {
        return BigDecimal.valueOf(amount)
                .movePointLeft(fractionDigitsOf(currencyCode))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static int fractionDigitsOf(String currencyCode) {
        int fractionDigits;
        try {
            fractionDigits = Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits();
        } catch (IllegalArgumentException ex) {
            fractionDigits = 2;
        }
        return fractionDigits < 0 ? 2 : fractionDigits;
    }

    /**
     * Package-private so {@link ReportService} can present the admin ticket list with exactly the
     * same shape (and the same seat resolution) as {@code /api/tickets/me}, instead of growing a
     * second, drifting mapping for admins.
     */
    TicketDto toDto(Ticket ticket) {
        ScheduleDto schedule = scheduleService.getScheduleById(ticket.getScheduleId());
        Map<Long, SeatDto> seats = seatsOfSchedule(ticket.getScheduleId());
        return toDto(ticket, schedule, seats, reservationsOf(ticket));
    }

    /** Page-level mapping: one reservation query plus one schedule/seat-grid lookup per distinct show. */
    List<TicketDto> toDtos(List<Ticket> tickets) {
        if (tickets.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SeatReservationDto>> reservations = seatReservationService.listReservationsOfTickets(
                tickets.stream().map(Ticket::getId).toList());
        Map<Long, ScheduleDto> schedules = new HashMap<>();
        Map<Long, Map<Long, SeatDto>> seatGrids = new HashMap<>();
        return tickets.stream().map(ticket -> {
            ScheduleDto schedule = schedules.computeIfAbsent(ticket.getScheduleId(), scheduleService::getScheduleById);
            Map<Long, SeatDto> seats = seatGrids.computeIfAbsent(schedule.theater().id(),
                    this::seatsOfTheater);
            return toDto(ticket, schedule, seats, reservations.getOrDefault(ticket.getId(), List.of()));
        }).toList();
    }

    private TicketDto toDto(Ticket ticket, ScheduleDto schedule, Map<Long, SeatDto> seats,
                            List<SeatReservationDto> reservations) {
        List<TicketSeatDto> ticketSeats = reservations.stream()
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
        return seatsOfTheater(scheduleService.getTheaterId(scheduleId));
    }

    private Map<Long, SeatDto> seatsOfTheater(Long theaterId) {
        Map<Long, SeatDto> seats = new LinkedHashMap<>();
        for (SeatDto seat : theaterService.listSeats(theaterId)) {
            seats.put(seat.id(), seat);
        }
        return seats;
    }

    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}

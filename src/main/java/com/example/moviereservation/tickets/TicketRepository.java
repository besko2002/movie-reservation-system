package com.example.moviereservation.tickets;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Package-private on purpose: {@code tickets} is owned by this module, so every other module goes
 * through {@link TicketService}.
 *
 * <p>The report queries at the bottom aggregate <em>in the database</em> ({@code count} /
 * {@code sum}, grouped per schedule and per day), so {@code ReportService} never loads a ticket row
 * just to add up money. {@link JpaSpecificationExecutor} backs the filtered admin ticket page.
 */
interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** "My tickets", newest first. */
    Page<Ticket> findByUserId(Long userId, Pageable pageable);

    /** The checkout idempotency lookup: does this user already wait for a payment here? */
    Optional<Ticket> findFirstByUserIdAndScheduleIdAndStatusOrderByIdDesc(Long userId,
                                                                         Long scheduleId,
                                                                         TicketStatus status);

    /** Webhook fallback when the event carries no ticket id: the session id is unique. */
    Optional<Ticket> findByStripeSessionId(String stripeSessionId);

    /** Webhook fallback for {@code charge.refunded} / {@code payment_intent.payment_failed}. */
    List<Ticket> findByStripePaymentIntentIdOrderByIdDesc(String stripePaymentIntentId);

    /**
     * Locks the ticket row for the duration of the current transaction. Every state transition uses
     * this instead of a plain read, so two deliveries (or a delivery racing a cancellation) cannot
     * both act on the same stale status — the second one waits and then sees the first one's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    /** Locked variant of {@link #findByStripeSessionId(String)} for the webhook fallback path. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.stripeSessionId = :stripeSessionId")
    Optional<Ticket> findByStripeSessionIdForUpdate(@Param("stripeSessionId") String stripeSessionId);

    /** Locked variant of {@link #findByStripePaymentIntentIdOrderByIdDesc(String)}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.stripePaymentIntentId = :paymentIntentId order by t.id desc")
    List<Ticket> findByStripePaymentIntentIdForUpdate(@Param("paymentIntentId") String paymentIntentId);

    /**
     * Serializes concurrent checkouts for one (user, schedule) pair. The lock is transaction-scoped,
     * so the second request waits until the first has committed its pending ticket and then sees it
     * in the idempotency lookup instead of creating a second ticket and a second Stripe session.
     *
     * <p>The key is derived in {@link TicketService}; a collision only over-serializes two unrelated
     * checkouts, never leaks state between them.
     */
    @Query(value = "select pg_advisory_xact_lock(:key)", nativeQuery = true)
    void lockCheckoutPair(@Param("key") long key);

    // --- reports (phase 8) ---------------------------------------------------------------------

    /** All captured tickets in the paid-at window, including ones refunded later. */
    @Query("""
            select new com.example.moviereservation.tickets.RevenueTotals(count(t), sum(t.totalPrice))
              from Ticket t
             where t.paidAt >= :from
               and t.paidAt < :to
            """)
    RevenueTotals totalsInPaidWindow(@Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to);

    /** Never add amounts of different currencies, even after a deployment changes its default. */
    @Query("""
            select distinct t.currency from Ticket t
             where (t.paidAt >= :from and t.paidAt < :to)
                or exists (select r.id from TicketRefund r where r.ticketId = t.id
                            and r.occurredAt >= :from and r.occurredAt < :to)
            """)
    List<String> currenciesInReportWindow(@Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to);

    /**
     * Revenue grouped per schedule inside a {@code paid_at} window. The caller turns the schedules
     * into movies through {@code ScheduleService}, because this module must not join another
     * module's tables.
     */
    @Query("""
            select new com.example.moviereservation.tickets.ScheduleRevenueRow(
                     t.scheduleId, count(t), sum(t.totalPrice))
              from Ticket t
             where t.paidAt >= :from
               and t.paidAt < :to
             group by t.scheduleId
             order by t.scheduleId
            """)
    List<ScheduleRevenueRow> revenueByScheduleInPaidWindow(@Param("from") OffsetDateTime from,
                                                           @Param("to") OffsetDateTime to);

    /**
     * Revenue grouped per calendar day of a business zone, inside a {@code paid_at} window.
     *
     * <p>Native SQL on purpose: only the database can turn a {@code TIMESTAMPTZ} into "the local day
     * in that zone" ({@code paid_at AT TIME ZONE :zone}), and grouping there keeps the whole daily
     * series a single round trip.
     *
     * @return rows of {@code [day, tickets, revenue]}, oldest day first
     */
    @Query(value = """
            select cast((t.paid_at at time zone :zone) as date) as day,
                   count(*) as tickets,
                   sum(t.total_price) as revenue
              from tickets t
             where t.paid_at >= :from
               and t.paid_at < :to
             group by 1
             order by 1
            """, nativeQuery = true)
    List<Object[]> revenueByDayInPaidWindow(@Param("zone") String zone,
                                            @Param("from") OffsetDateTime from,
                                            @Param("to") OffsetDateTime to);

    /**
     * Money actually taken for one schedule, whenever it was paid (the occupancy report shows the
     * revenue of the showtime, not of a window).
     *
     * @return the sum, or {@code null} when that schedule has no ticket of that status
     */
    @Query("""
            select sum(t.totalPrice)
              from Ticket t
             where t.scheduleId = :scheduleId
               and t.status = :status
            """)
    BigDecimal sumTotalPriceForScheduleAndStatus(@Param("scheduleId") Long scheduleId,
                                                 @Param("status") TicketStatus status);
}

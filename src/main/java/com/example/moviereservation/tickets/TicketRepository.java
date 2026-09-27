package com.example.moviereservation.tickets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
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
    List<Ticket> findByUserIdOrderByIdDesc(Long userId);

    /** The checkout idempotency lookup: does this user already wait for a payment here? */
    Optional<Ticket> findFirstByUserIdAndScheduleIdAndStatusOrderByIdDesc(Long userId,
                                                                         Long scheduleId,
                                                                         TicketStatus status);

    /** Webhook fallback when the event carries no ticket id: the session id is unique. */
    Optional<Ticket> findByStripeSessionId(String stripeSessionId);

    /** Webhook fallback for {@code charge.refunded} / {@code payment_intent.payment_failed}. */
    List<Ticket> findByStripePaymentIntentIdOrderByIdDesc(String stripePaymentIntentId);

    // --- reports (phase 8) ---------------------------------------------------------------------

    /**
     * Count and money of one status inside a {@code paid_at} window — the single query behind both
     * "what came in" (status PAID) and "what was given back" (status REFUNDED).
     *
     * @param status ticket status to add up
     * @param from   inclusive lower bound of {@code paid_at}
     * @param to     exclusive upper bound of {@code paid_at}
     * @return the aggregate; its {@code amount} is {@code null} when nothing matched
     */
    @Query("""
            select new com.example.moviereservation.tickets.RevenueTotals(count(t), sum(t.totalPrice))
              from Ticket t
             where t.status = :status
               and t.paidAt >= :from
               and t.paidAt < :to
            """)
    RevenueTotals totalsForStatusInPaidWindow(@Param("status") TicketStatus status,
                                             @Param("from") OffsetDateTime from,
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
             where t.status = :status
               and t.paidAt >= :from
               and t.paidAt < :to
             group by t.scheduleId
             order by t.scheduleId
            """)
    List<ScheduleRevenueRow> revenueByScheduleInPaidWindow(@Param("status") TicketStatus status,
                                                           @Param("from") OffsetDateTime from,
                                                           @Param("to") OffsetDateTime to);

    /**
     * Revenue grouped per calendar day of a business zone, inside a {@code paid_at} window.
     *
     * <p>Native SQL on purpose: only the database can turn a {@code TIMESTAMPTZ} into "the local day
     * in that zone" ({@code paid_at AT TIME ZONE :zone}), and grouping there keeps the whole daily
     * series a single round trip. {@code status} is bound as text because {@code tickets.status} is a
     * {@code VARCHAR} column with a {@code CHECK} constraint.
     *
     * @return rows of {@code [day, tickets, revenue]}, oldest day first
     */
    @Query(value = """
            select cast((t.paid_at at time zone :zone) as date) as day,
                   count(*) as tickets,
                   sum(t.total_price) as revenue
              from tickets t
             where t.status = :status
               and t.paid_at >= :from
               and t.paid_at < :to
             group by 1
             order by 1
            """, nativeQuery = true)
    List<Object[]> revenueByDayInPaidWindow(@Param("status") String status,
                                            @Param("zone") String zone,
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

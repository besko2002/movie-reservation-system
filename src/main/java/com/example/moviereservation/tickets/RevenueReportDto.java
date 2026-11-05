package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Revenue of a time window, as reported by {@code GET /api/admin/reports/revenue}.
 *
 * <h2>What counts as revenue</h2>
 * Gross counts captured tickets by {@code paid_at}, regardless of current status. Refunds are
 * positive deltas recorded when processed, independently of the payment date. Net is gross minus
 * refunds in the requested window, and can be negative when older sales are refunded today.
 *
 * @param from            inclusive start of the window (the effective one, defaults applied)
 * @param to              exclusive end of the window
 * @param currency        ISO 4217 currency every amount is expressed in ({@code app.stripe.currency})
 * @param paidTickets     number of tickets captured inside the window, even if later refunded
 * @param grossRevenue    sum of their {@code total_price}, scale 2
 * @param refundedTickets distinct tickets with a refund delta inside the window
 * @param refundedAmount  sum of refund deltas processed inside the window
 * @param netRevenue      {@code grossRevenue - refundedAmount}
 * @param byMovie         revenue per movie, richest first
 * @param byDay           revenue per calendar day of {@code app.schedule.zone}, oldest first
 */
public record RevenueReportDto(
        OffsetDateTime from,
        OffsetDateTime to,
        String currency,
        long paidTickets,
        BigDecimal grossRevenue,
        long refundedTickets,
        BigDecimal refundedAmount,
        BigDecimal netRevenue,
        List<MovieRevenueDto> byMovie,
        List<DailyRevenueDto> byDay
) {
}

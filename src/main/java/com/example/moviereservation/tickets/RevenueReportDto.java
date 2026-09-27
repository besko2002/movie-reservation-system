package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Revenue of a time window, as reported by {@code GET /api/admin/reports/revenue}.
 *
 * <h2>What counts as revenue</h2>
 * Exactly the tickets whose status is {@code PAID} and whose {@code paid_at} falls into
 * {@code [from, to)} — the money that arrived inside the window. Tickets that are
 * {@code PENDING_PAYMENT}, {@code CANCELLED} or {@code EXPIRED} never carry money and are ignored.
 *
 * <p>A ticket that was paid and then given back is {@code REFUNDED}, so it is <strong>not</strong>
 * {@code PAID} anymore and therefore never part of {@code grossRevenue}. It is reported separately
 * in {@code refundedTickets} / {@code refundedAmount} (counted by the same {@code paid_at} window,
 * so "what came in" and "what was given back again" describe the same set of payments).
 *
 * <p>Consequence: {@code netRevenue} equals {@code grossRevenue}. The field exists because a report
 * consumer expects it, and it is computed as {@code grossRevenue} rather than
 * {@code grossRevenue - refundedAmount} precisely because the refunded money was never added in the
 * first place; subtracting it would count every refund twice.
 *
 * @param from            inclusive start of the window (the effective one, defaults applied)
 * @param to              exclusive end of the window
 * @param currency        ISO 4217 currency every amount is expressed in ({@code app.stripe.currency})
 * @param paidTickets     number of PAID tickets paid inside the window
 * @param grossRevenue    sum of their {@code total_price}, scale 2
 * @param refundedTickets number of REFUNDED tickets that were originally paid inside the window
 * @param refundedAmount  sum of their {@code total_price}, scale 2 — money that was given back
 * @param netRevenue      {@code grossRevenue} (see above: refunds are already excluded)
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

package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the "revenue per day" breakdown of {@link RevenueReportDto}.
 *
 * <p>The day is the calendar day of the business zone {@code app.schedule.zone} (UTC by default) —
 * the same zone {@code GET /api/schedules?date=} is interpreted in, so a daily report and the
 * showtimes of that date talk about the same day. Days without a payment are not listed.
 *
 * @param date    calendar day in {@code app.schedule.zone}
 * @param tickets number of PAID tickets paid on that day
 * @param revenue sum of their {@code total_price}, scale 2
 */
public record DailyRevenueDto(
        LocalDate date,
        long tickets,
        BigDecimal revenue
) {
}

package com.example.moviereservation.tickets;

import java.math.BigDecimal;

/**
 * Aggregate projection of the revenue of one schedule, grouped in the database.
 *
 * <p>A ticket only stores its {@code schedule_id} — the movie behind it belongs to another module —
 * so the per-movie breakdown of {@link RevenueReportDto} is built by resolving these rows through
 * {@code ScheduleService} and adding them up per movie.
 *
 * @param scheduleId showtime
 * @param tickets    how many tickets matched
 * @param revenue    sum of their {@code total_price}, {@code null} for an empty result
 */
record ScheduleRevenueRow(Long scheduleId, Long tickets, BigDecimal revenue) {

    long ticketCount() {
        return tickets == null ? 0L : tickets;
    }

    BigDecimal revenueOrZero() {
        return revenue == null ? BigDecimal.ZERO : revenue;
    }
}

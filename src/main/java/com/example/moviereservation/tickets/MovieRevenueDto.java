package com.example.moviereservation.tickets;

import java.math.BigDecimal;

/**
 * One row of the "revenue per movie" breakdown of {@link RevenueReportDto}.
 *
 * @param movieId movie the schedules belong to
 * @param title   its title at the time the report was produced
 * @param tickets number of PAID tickets of that movie inside the window
 * @param revenue sum of their {@code total_price}, scale 2
 */
public record MovieRevenueDto(
        Long movieId,
        String title,
        long tickets,
        BigDecimal revenue
) {
}

package com.example.moviereservation.movies;

import java.math.BigDecimal;

/**
 * The two prices a schedule charges. Exposed as its own type so that the seat reservation and
 * ticket modules can compute a total without importing anything from the theaters module.
 *
 * @param basePrice price of a NORMAL seat
 * @param vipPrice  price of a VIP seat, never below {@code basePrice}
 */
public record SchedulePricesDto(
        BigDecimal basePrice,
        BigDecimal vipPrice
) {

    /**
     * @param category seat price category
     * @return the price charged for that category
     */
    public BigDecimal priceFor(ScheduleSeatCategory category) {
        return category == ScheduleSeatCategory.VIP ? vipPrice : basePrice;
    }

    static SchedulePricesDto from(MovieSchedule schedule) {
        return new SchedulePricesDto(schedule.getBasePrice(), schedule.getVipPrice());
    }
}

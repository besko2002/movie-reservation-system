package com.example.moviereservation.tickets;

import java.math.BigDecimal;

/**
 * Aggregate projection of one {@code select count(t), sum(t.totalPrice)} over the tickets table.
 *
 * <p>{@code amount} is {@code null} when no row matched (SQL {@code SUM} of an empty set), which is
 * why {@link #amountOrZero()} exists; {@code COALESCE} is deliberately not used in the query so the
 * JPQL stays free of vendor-specific numeric type juggling.
 *
 * @param tickets how many tickets matched
 * @param amount  sum of their {@code total_price}, {@code null} for an empty result
 */
record RevenueTotals(Long tickets, BigDecimal amount) {

    long ticketCount() {
        return tickets == null ? 0L : tickets;
    }

    BigDecimal amountOrZero() {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}

package com.example.moviereservation.tickets;

import java.math.BigDecimal;

/**
 * One payable line of a checkout — one seat.
 *
 * @param name        short product name, e.g. {@code "Seat A1 (VIP)"}
 * @param description longer description shown on the payment page
 * @param amount      price of this seat in the ticket's currency (major units, e.g. 10.00 USD)
 */
public record CheckoutLineItem(
        String name,
        String description,
        BigDecimal amount
) {
}

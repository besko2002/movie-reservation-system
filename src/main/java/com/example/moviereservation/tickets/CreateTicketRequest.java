package com.example.moviereservation.tickets;

import jakarta.validation.constraints.NotNull;

/**
 * Checkout request: which showtime to turn the caller's held seats into a ticket for. The seats
 * themselves are not sent — the server uses exactly the seats the caller currently holds, so a
 * client can never pay for a seat it does not hold.
 *
 * @param scheduleId showtime the caller holds seats in
 */
public record CreateTicketRequest(
        @NotNull(message = "scheduleId is required")
        Long scheduleId
) {
}

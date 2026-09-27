package com.example.moviereservation.tickets;

/**
 * Result of {@link TicketService#checkout}: the payload the client gets, plus whether this call is
 * what created the ticket.
 *
 * <p>The flag exists only so {@link TicketController} can answer {@code 201 Created} for a fresh
 * checkout and {@code 200 OK} when the idempotency rule handed back an existing pending ticket. It
 * is deliberately kept out of {@link TicketCheckoutDto}: the response body stays exactly the
 * documented set of ticket fields, and "was this created now" is HTTP semantics, not ticket data.
 *
 * @param checkout the ticket and its payment page
 * @param created  {@code true} when this call created the ticket, {@code false} when an existing
 *                 {@code PENDING_PAYMENT} ticket was returned unchanged
 */
public record TicketCheckoutResult(
        TicketCheckoutDto checkout,
        boolean created
) {
}

package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ApiError;
import com.example.moviereservation.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Tickets: check out the seats you hold, look at your tickets, cancel one.
 *
 * <p>Every endpoint needs a token. Someone else's ticket is answered with 403, not with a 404
 * disguise — the same decision the seat reservation module took for holds; an ADMIN may read and
 * cancel any ticket.
 *
 * <p>When the server runs without payment credentials, {@code POST /api/tickets} and the refund
 * half of {@code DELETE /api/tickets/{id}} answer <strong>503</strong> with an {@link ApiError};
 * the read endpoints keep working.
 */
@RestController
@RequestMapping("/api/tickets")
@Tag(name = "Tickets", description = "Checkout through Stripe, my tickets, cancellation and refund")
public class TicketController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Check out the seats you hold in a schedule (USER)",
            description = "Creates a PENDING_PAYMENT ticket for every seat the caller currently holds in that "
                    + "schedule and returns the hosted payment URL. The seats stay HELD until the payment "
                    + "webhook arrives, and their hold is extended to expiresAt - the same instant the payment "
                    + "page expires at (app.ticket.checkout-minutes), so the seats cannot be freed while the "
                    + "buyer is still paying. The call is idempotent per (user, schedule): while a PENDING_PAYMENT "
                    + "ticket exists for that schedule, the very same ticket and payment page are returned "
                    + "with 200 instead of 201, so a retry can never create a second payable ticket. Cancel "
                    + "the pending ticket first to check out a different set of seats.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ticket created, pay at checkoutUrl"),
            @ApiResponse(responseCode = "200",
                    description = "A PENDING_PAYMENT ticket for that schedule already existed and is returned "
                            + "unchanged with its payment page"),
            @ApiResponse(responseCode = "400", description = "Missing scheduleId",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The caller holds no seat in that schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "No payment provider is configured or reachable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TicketCheckoutDto> checkout(@AuthenticationPrincipal AuthenticatedUser principal,
                                                      @Valid @RequestBody CreateTicketRequest request) {
        TicketCheckoutResult result = ticketService.checkout(principal.id(), request);
        TicketCheckoutDto checkout = result.checkout();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(checkout.ticketId())
                .toUri();
        // 201 for a checkout this call created, 200 for the idempotent repeat of an existing one.
        // Location points at the ticket either way.
        return result.created()
                ? ResponseEntity.created(location).body(checkout)
                : ResponseEntity.ok().location(location).body(checkout);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The caller's tickets, newest first (USER)",
            description = "Every ticket of the token owner with its seats, whatever its status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tickets of the token owner"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<TicketDto> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ticketService.listMyTickets(principal.id());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One ticket (USER: your own; ADMIN: any)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The ticket with its seats"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The ticket belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown ticket",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TicketDto byId(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long id) {
        return ticketService.getTicket(id, principal.id(), ADMIN_ROLE.equals(principal.role()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cancel a ticket (USER: your own; ADMIN: any)",
            description = "A PENDING_PAYMENT ticket is cancelled and its seats are released. A PAID ticket is "
                    + "refunded in full and its seats are released, but only until "
                    + "app.ticket.cancellation-cutoff-hours before the showtime starts.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cancelled (and refunded when it was paid)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The ticket belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown ticket",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                    description = "The cancellation cutoff passed, or the ticket is already cancelled/refunded",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "No payment provider is configured or reachable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @PathVariable Long id) {
        ticketService.cancelTicket(id, principal.id(), ADMIN_ROLE.equals(principal.role()));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

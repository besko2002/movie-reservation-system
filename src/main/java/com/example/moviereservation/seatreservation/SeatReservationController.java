package com.example.moviereservation.seatreservation;

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
 * Timed seat holds: create, list your own, release.
 *
 * <p>All three endpoints need a token. Holding is all-or-nothing and ends in a 409 whenever a seat
 * is already taken, including when two clients pick the same seat at the same moment.
 */
@RestController
@RequestMapping("/api/seat-reservations")
@Tag(name = "Seat reservations", description = "Seat maps and timed seat holds")
public class SeatReservationController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final SeatReservationService seatReservationService;

    public SeatReservationController(SeatReservationService seatReservationService) {
        this.seatReservationService = seatReservationService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Hold seats of a schedule for the configured hold window (USER)",
            description = "Either every requested seat is held or none is. The hold expires after "
                    + "app.reservation.hold-minutes unless a ticket is paid for it (phase 7).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Seats held"),
            @ApiResponse(responseCode = "400",
                    description = "Empty seat list, duplicates, more than app.reservation.max-seats seats, "
                            + "a seat outside the schedule's theater, or a showtime that already started",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "At least one seat is already held or booked",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<SeatHoldDto> hold(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @Valid @RequestBody HoldSeatsRequest request) {
        SeatHoldDto hold = seatReservationService.holdSeats(principal.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(hold.reservationIds().get(0))
                .toUri();
        return ResponseEntity.created(location).body(hold);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The caller's reservations that still occupy a seat (USER)",
            description = "Live holds and confirmed seats; expired holds are not listed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active reservations of the token owner"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<SeatReservationDto> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return seatReservationService.getActiveReservationsForUser(principal.id());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Release one of your own holds (USER; an ADMIN may release any hold)",
            description = "Someone else's reservation is answered with 403. A paid (CONFIRMED) reservation is "
                    + "answered with 409: cancelling a paid ticket belongs to the tickets module.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Hold released, the seat is free again"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "The reservation belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown reservation",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The reservation is already paid or already released",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> release(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable Long id) {
        seatReservationService.releaseOwnHold(id, principal.id(), ADMIN_ROLE.equals(principal.role()));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

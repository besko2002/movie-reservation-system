package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ApiError;
import com.example.moviereservation.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Admin reporting: revenue of a window, occupancy of a showtime and every ticket in the system.
 *
 * <p>All three endpoints live under {@code /api/admin/**}, which {@code SecurityConfig} restricts to
 * the ADMIN role — anonymous callers get 401, a USER token gets 403. The {@code @PreAuthorize} below
 * states the same rule at the method, so the endpoints stay protected even if the path ever moves.
 *
 * <p>Everything here is read-only; no report changes a ticket, a seat or a schedule.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin reports", description = "Revenue, occupancy and the full ticket list (ADMIN)")
public class AdminReportController {

    private final ReportService reportService;

    public AdminReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/reports/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revenue of a time window (ADMIN)",
            description = "Sums total_price over the tickets that are PAID and whose paid_at falls into "
                    + "[from, to). Refunded tickets are not PAID anymore, so they are never part of "
                    + "grossRevenue; they are reported separately as refundedTickets/refundedAmount for the "
                    + "same paid_at window, which is why netRevenue equals grossRevenue (subtracting the "
                    + "refunds again would count them twice). PENDING_PAYMENT, CANCELLED and EXPIRED tickets "
                    + "carry no money and are ignored. Both bounds are optional ISO-8601 instants: from "
                    + "defaults to 30 days before to, and to defaults to now. byDay groups the payments by "
                    + "calendar day of the business zone app.schedule.zone (UTC by default).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The revenue report"),
            @ApiResponse(responseCode = "400", description = "from is after to, or a bound is not an instant",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public RevenueReportDto revenue(
            @Parameter(description = "Inclusive start instant; defaults to 30 days before `to`")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive end instant; defaults to now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return reportService.revenue(from, to);
    }

    @GetMapping("/reports/occupancy/{scheduleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "How full one showtime is (ADMIN)",
            description = "Capacity of the hall, paid seats (CONFIRMED), live holds (HELD and not expired), "
                    + "available seats, the occupancy percentage (bookedSeats / capacity * 100, scale 2) and "
                    + "the money taken for that schedule (sum of its PAID tickets). Holds are reported but "
                    + "deliberately not counted as occupancy: a hold is not a sale.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The occupancy report"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public OccupancyReportDto occupancy(@PathVariable Long scheduleId) {
        return reportService.occupancy(scheduleId);
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Every ticket in the system, newest first (ADMIN)",
            description = "The admin counterpart of GET /api/tickets/me: all tickets of all users with their "
                    + "seats, optionally filtered by status, schedule or buyer. Paginated; size is clamped "
                    + "to 100.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of tickets, newest first"),
            @ApiResponse(responseCode = "400", description = "Unknown status value",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public PageResponse<TicketDto> tickets(
            @Parameter(description = "PENDING_PAYMENT, PAID, CANCELLED, REFUNDED or EXPIRED")
            @RequestParam(required = false) String status,
            @Parameter(description = "Only tickets of this showtime")
            @RequestParam(required = false) Long scheduleId,
            @Parameter(description = "Only tickets of this buyer")
            @RequestParam(required = false) Long userId,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, clamped to 100")
            @RequestParam(required = false) Integer size) {
        return reportService.listAllTickets(status, scheduleId, userId, page, size);
    }
}

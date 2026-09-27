package com.example.moviereservation.seatreservation;

import com.example.moviereservation.common.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seat map of a schedule: {@code GET /api/schedules/{id}/seats}, public like every other read under
 * {@code /api/schedules}.
 *
 * <p>The endpoint lives in the seat reservation module because the <em>status</em> of a seat is
 * reservation data; the movies module's {@code ScheduleController} only maps {@code /api/schedules}
 * and {@code /api/schedules/{id}}, so exactly one handler maps this path.
 */
@RestController
@RequestMapping("/api/schedules/{scheduleId}/seats")
@Tag(name = "Seat reservations", description = "Seat maps and timed seat holds")
public class ScheduleSeatMapController {

    private final SeatReservationService seatReservationService;

    public ScheduleSeatMapController(SeatReservationService seatReservationService) {
        this.seatReservationService = seatReservationService;
    }

    @GetMapping
    @Operation(summary = "Seat map of a schedule with per-seat price and status (public)",
            description = "Status is AVAILABLE, HELD or BOOKED. A hold whose expiry has passed is already "
                    + "reported as AVAILABLE, even before the expiry sweeper released it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seat map"),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public SeatMapDto seatMap(@PathVariable Long scheduleId) {
        return seatReservationService.getSeatMap(scheduleId);
    }
}

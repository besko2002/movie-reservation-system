package com.example.moviereservation.movies;

import com.example.moviereservation.common.ApiError;
import com.example.moviereservation.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Movie schedules (showtimes): reading is public, writing requires an ADMIN token.
 *
 * <p>The end time is never part of a request body: it is computed as
 * {@code startTime + movie duration + cleaning buffer} ({@code app.schedule.buffer-minutes}).
 */
@RestController
@RequestMapping("/api/schedules")
@Tag(name = "Schedules", description = "Movie schedules (showtimes) with per-seat-type prices")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    @Operation(summary = "List schedules with paging and optional filters",
            description = "The 'date' filter selects schedules whose startTime falls on that local date in the "
                    + "business zone configured by app.schedule.zone (UTC by default). 'from'/'to' take ISO-8601 "
                    + "instants with an offset and are compared against startTime as [from, to).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of schedules"),
            @ApiResponse(responseCode = "400", description = "Unknown sort property or malformed date/instant",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public PageResponse<ScheduleSummaryDto> list(
            @Parameter(description = "Only schedules of this movie")
            @RequestParam(required = false) Long movieId,
            @Parameter(description = "Only schedules in this theater")
            @RequestParam(required = false) Long theaterId,
            @Parameter(description = "Local date (yyyy-MM-dd) in the app's business zone")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Only schedules starting at or after this instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Only schedules starting strictly before this instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, clamped to 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as property[,asc|desc]; defaults to startTime,asc")
            @RequestParam(required = false) List<String> sort) {
        return scheduleService.listSchedules(movieId, theaterId, date, from, to, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one schedule with its movie, theater and prices")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule"),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ScheduleDto get(@PathVariable Long id) {
        return scheduleService.getScheduleById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a schedule; endTime is computed by the server (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Schedule created"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, unknown movieId/theaterId, startTime not in the future, "
                            + "or vipPrice below basePrice",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The theater is already occupied in that window",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ScheduleDto> create(@Valid @RequestBody CreateScheduleRequest request) {
        ScheduleDto created = scheduleService.createSchedule(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a schedule; endTime is recomputed and the overlap check runs again (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule updated"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed, unknown movieId/theaterId, or vipPrice below basePrice",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The theater is already occupied in that window",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ScheduleDto update(@PathVariable Long id, @Valid @RequestBody UpdateScheduleRequest request) {
        return scheduleService.updateSchedule(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a schedule (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Schedule deleted"),
            @ApiResponse(responseCode = "404", description = "Unknown schedule",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The schedule is in use (from phase 6 on)",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleService.deleteSchedule(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

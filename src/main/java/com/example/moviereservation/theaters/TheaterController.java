package com.example.moviereservation.theaters;

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
import java.util.List;

/** Theaters (halls) and their seat maps: reading is public, writing requires an ADMIN token. */
@RestController
@RequestMapping("/api/theaters")
@Tag(name = "Theaters", description = "Theaters (halls) and their seats")
public class TheaterController {

    private final TheaterService theaterService;

    public TheaterController(TheaterService theaterService) {
        this.theaterService = theaterService;
    }

    @GetMapping
    @Operation(summary = "List theaters with paging")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of theaters"),
            @ApiResponse(responseCode = "400", description = "Unknown sort property",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public PageResponse<TheaterSummaryDto> list(
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, clamped to 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as property[,asc|desc]")
            @RequestParam(required = false) List<String> sort) {
        return theaterService.listTheaters(page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one theater including its seat counts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Theater"),
            @ApiResponse(responseCode = "404", description = "Unknown theater",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TheaterDto get(@PathVariable Long id) {
        return theaterService.getTheaterById(id);
    }

    @GetMapping("/{id}/seats")
    @Operation(summary = "Seat map of a theater, ordered by row label then seat number")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seats of the theater"),
            @ApiResponse(responseCode = "404", description = "Unknown theater",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public List<SeatDto> seats(@PathVariable Long id) {
        return theaterService.listSeats(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a theater and generate its seats (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Theater created with its seats"),
            @ApiResponse(responseCode = "400", description = "Validation failed or unknown VIP row",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Theater name already exists",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TheaterDto> create(@Valid @RequestBody CreateTheaterRequest request) {
        TheaterDto created = theaterService.createTheater(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a theater; seats are regenerated only when the grid changes (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Theater updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed or unknown VIP row",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown theater",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Name already exists, or the seats are in use",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TheaterDto update(@PathVariable Long id, @Valid @RequestBody UpdateTheaterRequest request) {
        return theaterService.updateTheater(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a theater and its seats (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Theater deleted"),
            @ApiResponse(responseCode = "409", description = "Theater still has schedules",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown theater",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        theaterService.deleteTheater(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

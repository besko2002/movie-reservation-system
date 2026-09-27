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

/** Movie catalogue: reading is public, writing requires an ADMIN token. */
@RestController
@RequestMapping("/api/movies")
@Tag(name = "Movies", description = "Movie catalogue")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    @Operation(summary = "List movies with paging and optional genre/title filters")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of movies"),
            @ApiResponse(responseCode = "400", description = "Unknown sort property",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public PageResponse<MovieSummaryDto> list(
            @Parameter(description = "Genre name, case-insensitive")
            @RequestParam(required = false) String genre,
            @Parameter(description = "Title fragment, case-insensitive")
            @RequestParam(required = false) String title,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, clamped to 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as property[,asc|desc]")
            @RequestParam(required = false) List<String> sort) {
        return movieService.listMovies(genre, title, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one movie including its genres")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movie"),
            @ApiResponse(responseCode = "404", description = "Unknown movie",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public MovieDto get(@PathVariable Long id) {
        return movieService.getMovieById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a movie (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Movie created"),
            @ApiResponse(responseCode = "400", description = "Validation failed or unknown genre id",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<MovieDto> create(@Valid @RequestBody CreateMovieRequest request) {
        MovieDto created = movieService.createMovie(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a movie, genre set included (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movie updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed or unknown genre id",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown movie",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public MovieDto update(@PathVariable Long id, @Valid @RequestBody UpdateMovieRequest request) {
        return movieService.updateMovie(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a movie (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Movie deleted"),
            @ApiResponse(responseCode = "409", description = "Movie still has schedules",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Unknown movie",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        movieService.deleteMovie(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

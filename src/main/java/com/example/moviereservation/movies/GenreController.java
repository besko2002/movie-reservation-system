package com.example.moviereservation.movies;

import com.example.moviereservation.common.ApiError;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/** Genre catalogue: reading is public, writing requires an ADMIN token. */
@RestController
@RequestMapping("/api/genres")
@Tag(name = "Genres", description = "Movie genres")
public class GenreController {

    private final GenreService genreService;

    public GenreController(GenreService genreService) {
        this.genreService = genreService;
    }

    @GetMapping
    @Operation(summary = "List all genres, sorted by name")
    @ApiResponse(responseCode = "200", description = "Genres")
    public List<GenreDto> list() {
        return genreService.getAllGenres();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one genre")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Genre"),
            @ApiResponse(responseCode = "404", description = "Unknown genre",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public GenreDto get(@PathVariable Long id) {
        return genreService.getGenreById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a genre (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Genre created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Genre name already exists",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<GenreDto> create(@Valid @RequestBody GenreRequest request) {
        GenreDto created = genreService.createGenre(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename a genre (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Genre updated"),
            @ApiResponse(responseCode = "404", description = "Unknown genre",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Genre name already exists",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public GenreDto update(@PathVariable Long id, @Valid @RequestBody GenreRequest request) {
        return genreService.updateGenre(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a genre (ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Genre deleted"),
            @ApiResponse(responseCode = "404", description = "Unknown genre",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Genre still attached to a movie",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        genreService.deleteGenre(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}

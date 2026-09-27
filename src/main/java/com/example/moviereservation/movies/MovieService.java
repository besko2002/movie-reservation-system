package com.example.moviereservation.movies;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.PageResponse;
import com.example.moviereservation.common.ResourceNotFoundException;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Public API of the movies module: catalogue browsing and administration.
 *
 * <p>Other modules must depend on this service, never on {@code MovieRepository}. Later phases
 * (schedules) use {@link #getMovieById(Long)}, {@link #movieExists(Long)} and
 * {@link #getDurationMinutes(Long)}.
 */
@Service
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    /** Largest page a client may ask for; bigger values are clamped down to it. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Page size used when the client does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Sortable properties; anything else is rejected instead of leaking a 500. */
    private static final Set<String> SORTABLE = Set.of(
            "id", "title", "durationMinutes", "releaseDate", "createdAt");

    private static final char LIKE_ESCAPE = '\\';

    private final MovieRepository movieRepository;
    private final GenreService genreService;

    /**
     * Deletion guard: schedules live in this same module, and {@code movie_schedules} references
     * {@code movies(id)} with {@code ON DELETE RESTRICT}, so a movie that is still scheduled is
     * refused with a 409 instead of letting the foreign key surface as a 500.
     */
    private final ScheduleService scheduleService;

    public MovieService(MovieRepository movieRepository, GenreService genreService,
                        ScheduleService scheduleService) {
        this.movieRepository = movieRepository;
        this.genreService = genreService;
        this.scheduleService = scheduleService;
    }

    /**
     * Lists movies, optionally filtered by genre name and/or title fragment (both
     * case-insensitive).
     *
     * @param genre    exact genre name, ignoring case; {@code null} for no genre filter
     * @param title    title fragment, ignoring case; {@code null} for no title filter
     * @param page     zero-based page index; negative values are treated as 0
     * @param size     page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     * @param sort     {@code property[,asc|desc]} entries
     * @return a stable page envelope of {@link MovieSummaryDto}
     * @throws BadRequestException when a sort property is unknown
     */
    @Transactional(readOnly = true)
    public PageResponse<MovieSummaryDto> listMovies(String genre, String title, Integer page, Integer size,
                                                    List<String> sort) {
        Pageable pageable = toPageable(page, size, sort);
        Page<Movie> movies = movieRepository.findAll(searchSpecification(genre, title), pageable);
        return PageResponse.of(movies, movies.getContent().stream().map(MovieSummaryDto::from).toList());
    }

    /**
     * @return the movie with its genres
     * @throws ResourceNotFoundException when no movie has that id
     */
    @Transactional(readOnly = true)
    public MovieDto getMovieById(Long id) {
        return movieRepository.findWithGenresById(id)
                .map(MovieDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Movie", id));
    }

    /** @return whether a movie with that id exists (used by the schedules module) */
    @Transactional(readOnly = true)
    public boolean movieExists(Long id) {
        return id != null && movieRepository.existsById(id);
    }

    /**
     * @return the running time in minutes, for schedule/showtime calculations
     * @throws ResourceNotFoundException when no movie has that id
     */
    @Transactional(readOnly = true)
    public int getDurationMinutes(Long id) {
        return movieRepository.findById(id)
                .map(Movie::getDurationMinutes)
                .orElseThrow(() -> new ResourceNotFoundException("Movie", id));
    }

    /**
     * Creates a movie and attaches the requested genres.
     *
     * @throws BadRequestException when a genre id does not exist
     */
    @Transactional
    public MovieDto createMovie(CreateMovieRequest request) {
        Movie movie = new Movie(
                request.title(),
                request.description(),
                request.posterUrl(),
                request.durationMinutes(),
                request.releaseDate());
        movie.replaceGenres(genreService.resolveGenres(request.genreIds()));
        movie = movieRepository.saveAndFlush(movie);
        log.info("Created movie id={} genres={}", movie.getId(), movie.getGenres().size());
        return MovieDto.from(movie);
    }

    /**
     * Full update: every field is replaced, including the genre set.
     *
     * @throws ResourceNotFoundException when no movie has that id
     * @throws BadRequestException       when a genre id does not exist
     */
    @Transactional
    public MovieDto updateMovie(Long id, UpdateMovieRequest request) {
        Movie movie = movieRepository.findWithGenresById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movie", id));
        boolean durationChanged = movie.getDurationMinutes() != request.durationMinutes();
        movie.setTitle(request.title());
        movie.setDescription(request.description());
        movie.setPosterUrl(request.posterUrl());
        movie.setDurationMinutes(request.durationMinutes());
        movie.setReleaseDate(request.releaseDate());
        movie.replaceGenres(genreService.resolveGenres(request.genreIds()));
        movieRepository.flush();
        if (durationChanged) {
            scheduleService.applyMovieDuration(id, request.durationMinutes());
        }
        return MovieDto.from(movie);
    }

    /**
     * Deletes a movie.
     *
     * @throws ResourceNotFoundException when no movie has that id
     * @throws ConflictException         when the movie still has schedules, or when another
     *                                   {@code ON DELETE RESTRICT} reference remains
     */
    @Transactional
    public void deleteMovie(Long id) {
        Movie movie = movieRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movie", id));
        // The normal path: an explicit check, so the answer names the blocker.
        long schedules = scheduleService.countSchedulesForMovie(id);
        if (schedules > 0) {
            throw new ConflictException(
                    ("Movie %d still has %d schedule(s); delete them before deleting the movie")
                            .formatted(id, schedules));
        }
        // movie_genres rows go with it (ON DELETE CASCADE plus the owned collection).
        movieRepository.delete(movie);
        try {
            movieRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // Safety net only: a reference inserted concurrently must still be a 409, not a 500.
            throw new ConflictException("Movie %d is still referenced and cannot be deleted".formatted(id));
        }
        log.info("Deleted movie id={}", id);
    }

    private static Specification<Movie> searchSpecification(String genre, String title) {
        String genreFilter = trimToNull(genre);
        String titleFilter = trimToNull(title);
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (titleFilter != null) {
                predicates.add(builder.like(
                        builder.lower(root.get("title")),
                        "%" + escapeLike(titleFilter.toLowerCase(Locale.ROOT)) + "%",
                        LIKE_ESCAPE));
            }
            if (genreFilter != null) {
                // Inner join: a movie without that genre must not match.
                predicates.add(builder.equal(
                        builder.lower(root.join("genres", JoinType.INNER).get("name")),
                        genreFilter.toLowerCase(Locale.ROOT)));
                if (query != null) {
                    // A movie could otherwise repeat once per matching join row.
                    query.distinct(true);
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Pageable toPageable(Integer page, Integer size, List<String> sort) {
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(pageNumber, pageSize, toSort(sort));
    }

    private static Sort toSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(Sort.Order.asc("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        // Spring already splits "sort=title,desc" into two request values, so a direction token
        // can arrive on its own; it then applies to the property that precedes it.
        for (String entry : sort) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            for (String token : entry.split(",")) {
                String value = token.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (isDirection(value)) {
                    if (orders.isEmpty()) {
                        throw new BadRequestException("Sort direction '%s' has no property".formatted(value));
                    }
                    Sort.Order last = orders.remove(orders.size() - 1);
                    orders.add(last.with("desc".equalsIgnoreCase(value) ? Sort.Direction.DESC : Sort.Direction.ASC));
                    continue;
                }
                if (!SORTABLE.contains(value)) {
                    throw new BadRequestException(
                            "Unknown sort property '%s'; allowed: %s".formatted(value, sorted(SORTABLE)));
                }
                orders.add(Sort.Order.asc(value));
            }
        }
        return orders.isEmpty() ? Sort.by(Sort.Order.asc("id")) : Sort.by(orders);
    }

    private static boolean isDirection(String value) {
        return "asc".equalsIgnoreCase(value) || "desc".equalsIgnoreCase(value);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    /** Makes {@code %} and {@code _} typed by the user match themselves instead of acting as wildcards. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

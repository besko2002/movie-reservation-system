package com.example.moviereservation.movies;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.PageResponse;
import com.example.moviereservation.common.ResourceNotFoundException;
import com.example.moviereservation.theaters.TheaterDto;
import com.example.moviereservation.theaters.TheaterService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public API of the movie schedules (showtimes) part of the movies module.
 *
 * <p>Two rules shape everything here:
 * <ul>
 *   <li><strong>end time is derived.</strong> A client only sends {@code startTime}; the end is
 *       {@code startTime + movie.durationMinutes + app.schedule.buffer-minutes}. The cleaning
 *       buffer is part of the stored {@code end_time}, so every overlap check automatically keeps
 *       the theater free while it is being cleaned.</li>
 *   <li><strong>no overlap per theater.</strong> A new or moved schedule is rejected with 409 when
 *       {@code newStart < existing.end AND newEnd > existing.start}. Back-to-back showtimes that
 *       only touch are allowed.</li>
 * </ul>
 *
 * <p>Other modules must depend on this service, never on {@code MovieScheduleRepository}. Phases
 * 6 and 7 use {@link #getScheduleById(Long)}, {@link #scheduleExists(Long)},
 * {@link #getTheaterId(Long)}, {@link #getStartTime(Long)}, {@link #getEndTime(Long)},
 * {@link #getMovieId(Long)}, {@link #getPrices(Long)} and
 * {@link #getPrice(Long, ScheduleSeatCategory)}.
 *
 * <p>Theater data is read through {@link TheaterService}; the theaters module's entities are never
 * touched from here.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    /** Largest page a client may ask for; bigger values are clamped down to it. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Page size used when the client does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Sortable properties; anything else is rejected instead of leaking a 500. */
    private static final Set<String> SORTABLE = Set.of(
            "id", "startTime", "endTime", "basePrice", "vipPrice", "createdAt");

    /** Sentinel for "exclude nothing", used by the overlap query on create. */
    private static final long NO_EXCLUDED_ID = -1L;

    private final MovieScheduleRepository scheduleRepository;
    private final MovieRepository movieRepository;
    private final TheaterService theaterService;

    /**
     * Deletion seam: empty in phase 5, so every schedule may be deleted. As soon as a later phase
     * publishes a {@link ScheduleUsagePolicy} bean (schedules referenced by reservations or
     * tickets), it is consulted here without any other change to this service.
     */
    private final ObjectProvider<ScheduleUsagePolicy> scheduleUsagePolicy;

    private final int bufferMinutes;
    private final ZoneId zone;

    public ScheduleService(MovieScheduleRepository scheduleRepository,
                           MovieRepository movieRepository,
                           TheaterService theaterService,
                           ObjectProvider<ScheduleUsagePolicy> scheduleUsagePolicy,
                           @Value("${app.schedule.buffer-minutes}") int bufferMinutes,
                           @Value("${app.schedule.zone}") String zone) {
        this.scheduleRepository = scheduleRepository;
        this.movieRepository = movieRepository;
        this.theaterService = theaterService;
        this.scheduleUsagePolicy = scheduleUsagePolicy;
        this.bufferMinutes = bufferMinutes;
        this.zone = ZoneId.of(zone);
    }

    /** @return the cleaning buffer in minutes that is added to every computed end time */
    public int getBufferMinutes() {
        return bufferMinutes;
    }

    /** @return the business time zone a {@code date} filter is interpreted in */
    public ZoneId getZone() {
        return zone;
    }

    // --- reads ---------------------------------------------------------------------------------

    /**
     * Lists schedules, newest start first by default.
     *
     * @param movieId   only schedules of that movie; {@code null} for no filter
     * @param theaterId only schedules in that theater; {@code null} for no filter
     * @param date      only schedules whose {@code startTime} falls on that local date in the
     *                  configured business zone ({@code app.schedule.zone}, UTC by default);
     *                  {@code null} for no filter
     * @param from      only schedules starting at or after this instant; {@code null} for no filter
     * @param to        only schedules starting strictly before this instant; {@code null} for none
     * @param page      zero-based page index; negative values are treated as 0
     * @param size      page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     * @param sort      {@code property[,asc|desc]} entries; defaults to {@code startTime,asc}
     * @return a stable page envelope of {@link ScheduleSummaryDto}
     * @throws BadRequestException when a sort property is unknown
     */
    @Transactional(readOnly = true)
    public PageResponse<ScheduleSummaryDto> listSchedules(Long movieId, Long theaterId, LocalDate date,
                                                          OffsetDateTime from, OffsetDateTime to,
                                                          Integer page, Integer size, List<String> sort) {
        Pageable pageable = toPageable(page, size, sort);
        Page<MovieSchedule> schedules =
                scheduleRepository.findAll(filterSpecification(movieId, theaterId, date, from, to), pageable);

        // One theater lookup per distinct theater on the page, not one per row.
        Map<Long, String> theaterNames = new HashMap<>();
        List<ScheduleSummaryDto> content = schedules.getContent().stream()
                .map(schedule -> ScheduleSummaryDto.from(
                        schedule,
                        theaterNames.computeIfAbsent(schedule.getTheaterId(), this::theaterNameOf)))
                .toList();
        return PageResponse.of(schedules, content);
    }

    /**
     * @return the schedule with its movie summary, theater summary and prices
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public ScheduleDto getScheduleById(Long id) {
        MovieSchedule schedule = requireSchedule(id);
        return ScheduleDto.from(schedule, ScheduleTheaterDto.from(theaterOf(schedule.getTheaterId())), bufferMinutes);
    }

    /** @return whether a schedule with that id exists (used by seat reservations and tickets) */
    @Transactional(readOnly = true)
    public boolean scheduleExists(Long id) {
        return id != null && scheduleRepository.existsById(id);
    }

    /**
     * @return the theater the schedule plays in, so the seat reservation module can load its seats
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public Long getTheaterId(Long id) {
        return requireSchedule(id).getTheaterId();
    }

    /**
     * @return the movie the schedule plays
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public Long getMovieId(Long id) {
        return requireSchedule(id).getMovie().getId();
    }

    /**
     * @return when the showtime starts, for the ticket cancellation cutoff
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public OffsetDateTime getStartTime(Long id) {
        return requireSchedule(id).getStartTime();
    }

    /**
     * @return when the theater is free again (cleaning buffer included)
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public OffsetDateTime getEndTime(Long id) {
        return requireSchedule(id).getEndTime();
    }

    /**
     * @return both seat prices of the schedule; call {@link SchedulePricesDto#priceFor}
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public SchedulePricesDto getPrices(Long id) {
        return SchedulePricesDto.from(requireSchedule(id));
    }

    /**
     * Price of one seat of the given category, so the reservation module can price a seat without
     * knowing anything about the theaters module.
     *
     * @param id       schedule id
     * @param category seat price category
     * @return the price charged for that seat
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public BigDecimal getPrice(Long id, ScheduleSeatCategory category) {
        return getPrices(id).priceFor(category);
    }

    /**
     * How many schedules still play in that theater. The theaters module asks this (through
     * {@code TheaterUsagePolicy}) before deleting a theater, because {@code movie_schedules}
     * references {@code theaters(id)} with {@code ON DELETE RESTRICT}.
     *
     * @param theaterId theater id; {@code null} counts as zero
     * @return the number of schedules in that theater
     */
    @Transactional(readOnly = true)
    public long countSchedulesForTheater(Long theaterId) {
        return theaterId == null ? 0L : scheduleRepository.countByTheaterId(theaterId);
    }

    /** @return whether any schedule plays in that theater */
    @Transactional(readOnly = true)
    public boolean hasSchedulesForTheater(Long theaterId) {
        return countSchedulesForTheater(theaterId) > 0;
    }

    /**
     * How many schedules still play that movie; consulted before a movie is deleted, because
     * {@code movie_schedules} references {@code movies(id)} with {@code ON DELETE RESTRICT}.
     *
     * @param movieId movie id; {@code null} counts as zero
     * @return the number of schedules of that movie
     */
    @Transactional(readOnly = true)
    public long countSchedulesForMovie(Long movieId) {
        return movieId == null ? 0L : scheduleRepository.countByMovie_Id(movieId);
    }

    /** @return whether any schedule plays that movie */
    @Transactional(readOnly = true)
    public boolean hasSchedulesForMovie(Long movieId) {
        return countSchedulesForMovie(movieId) > 0;
    }

    // --- writes --------------------------------------------------------------------------------

    /**
     * Creates a schedule. The end time is computed, and the theater must be free for the whole
     * computed window.
     *
     * @throws BadRequestException when the movie or theater is unknown, when {@code startTime} is
     *                             not in the future, or when {@code vipPrice < basePrice}
     * @throws ConflictException   when another schedule already occupies that theater window
     */
    @Transactional
    public ScheduleDto createSchedule(CreateScheduleRequest request) {
        Movie movie = requireMovie(request.movieId());
        TheaterDto theater = requireTheater(request.theaterId());
        requirePriceOrder(request.basePrice(), request.vipPrice());

        OffsetDateTime start = request.startTime();
        if (!start.isAfter(OffsetDateTime.now())) {
            throw new BadRequestException("startTime must be in the future, but was " + format(start));
        }
        OffsetDateTime end = endTimeOf(start, movie.getDurationMinutes());
        requireNoOverlap(theater.id(), start, end, NO_EXCLUDED_ID);

        MovieSchedule schedule = scheduleRepository.saveAndFlush(new MovieSchedule(
                movie, theater.id(), start, end,
                money(request.basePrice()), money(request.vipPrice())));
        log.info("Created schedule id={} movieId={} theaterId={} window={}..{}",
                schedule.getId(), movie.getId(), theater.id(), format(start), format(end));
        return ScheduleDto.from(schedule, ScheduleTheaterDto.from(theater), bufferMinutes);
    }

    /**
     * Full update: the end time is recomputed from the (possibly new) movie and start time, and the
     * overlap check runs again while ignoring this schedule, so moving a schedule never collides
     * with itself.
     *
     * @throws ResourceNotFoundException when no schedule has that id
     * @throws BadRequestException       when the movie or theater is unknown, or the prices are
     *                                   inconsistent
     * @throws ConflictException         when the new window collides with another schedule
     */
    @Transactional
    public ScheduleDto updateSchedule(Long id, UpdateScheduleRequest request) {
        MovieSchedule schedule = requireSchedule(id);
        Movie movie = requireMovie(request.movieId());
        TheaterDto theater = requireTheater(request.theaterId());
        requirePriceOrder(request.basePrice(), request.vipPrice());

        OffsetDateTime start = request.startTime();
        boolean moved = !schedule.getTheaterId().equals(theater.id())
                || !schedule.getMovie().getId().equals(movie.getId())
                || !schedule.getStartTime().isEqual(start);
        if (moved) {
            scheduleUsagePolicy.ifAvailable(policy -> policy.checkScheduleMayBeMoved(id));
        }
        OffsetDateTime end = endTimeOf(start, movie.getDurationMinutes());
        requireNoOverlap(theater.id(), start, end, id);

        schedule.setMovie(movie);
        schedule.setTheaterId(theater.id());
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setBasePrice(money(request.basePrice()));
        schedule.setVipPrice(money(request.vipPrice()));
        scheduleRepository.flush();
        log.info("Updated schedule id={} theaterId={} window={}..{}", id, theater.id(), format(start), format(end));
        return ScheduleDto.from(schedule, ScheduleTheaterDto.from(theater), bufferMinutes);
    }

    /**
     * Recomputes the end time of every schedule of a movie whose duration changed, so the stored
     * windows (and therefore the overlap rule) keep matching the real running time.
     *
     * @throws ConflictException when a longer window would now collide with another schedule
     */
    @Transactional
    void applyMovieDuration(Long movieId, int durationMinutes) {
        List<MovieSchedule> schedules = scheduleRepository.findByMovie_Id(movieId);
        for (MovieSchedule schedule : schedules) {
            schedule.setEndTime(endTimeOf(schedule.getStartTime(), durationMinutes));
        }
        scheduleRepository.flush();
        for (MovieSchedule schedule : schedules) {
            requireNoOverlap(schedule.getTheaterId(), schedule.getStartTime(), schedule.getEndTime(), schedule.getId());
        }
    }

    /**
     * Deletes a schedule.
     *
     * @throws ResourceNotFoundException when no schedule has that id
     * @throws ConflictException         when a {@link ScheduleUsagePolicy} refuses the deletion
     *                                   (from phase 6 on: the schedule has reservations/tickets)
     */
    @Transactional
    public void deleteSchedule(Long id) {
        MovieSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", id));
        // --- deletion seam --------------------------------------------------------------------
        // No bean implements ScheduleUsagePolicy in phase 5, so nothing is consulted yet.
        scheduleUsagePolicy.ifAvailable(policy -> policy.checkScheduleMayBeDeleted(id));
        scheduleRepository.delete(schedule);
        log.info("Deleted schedule id={}", id);
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * Normalises a price to the two decimals of {@code NUMERIC(10,2)}, so a response never shows a
     * different scale than the stored row. Validation already rejects more than two decimals, so
     * this only ever pads.
     */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** The theater is blocked until the movie has finished <em>and</em> been cleaned. */
    private OffsetDateTime endTimeOf(OffsetDateTime start, int durationMinutes) {
        return start.plusMinutes((long) durationMinutes + bufferMinutes);
    }

    private void requireNoOverlap(Long theaterId, OffsetDateTime start, OffsetDateTime end, Long excludeId) {
        List<MovieSchedule> clashes = scheduleRepository.findOverlapping(theaterId, start, end, excludeId);
        if (!clashes.isEmpty()) {
            MovieSchedule clash = clashes.get(0);
            throw new ConflictException(
                    ("Theater %d is already occupied between %s and %s by schedule %d "
                            + "(cleaning buffer of %d minutes included)")
                            .formatted(theaterId, format(clash.getStartTime()), format(clash.getEndTime()),
                                    clash.getId(), bufferMinutes));
        }
    }

    private Movie requireMovie(Long movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new BadRequestException("Unknown movieId " + movieId));
    }

    private TheaterDto requireTheater(Long theaterId) {
        if (!theaterService.theaterExists(theaterId)) {
            throw new BadRequestException("Unknown theaterId " + theaterId);
        }
        return theaterService.getTheaterById(theaterId);
    }

    private static void requirePriceOrder(BigDecimal basePrice, BigDecimal vipPrice) {
        if (vipPrice.compareTo(basePrice) < 0) {
            throw new BadRequestException(
                    "vipPrice (%s) must not be lower than basePrice (%s)".formatted(vipPrice, basePrice));
        }
    }

    private MovieSchedule requireSchedule(Long id) {
        if (id == null) {
            throw new ResourceNotFoundException("Schedule", null);
        }
        return scheduleRepository.findWithMovieById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", id));
    }

    private TheaterDto theaterOf(Long theaterId) {
        return theaterService.getTheaterById(theaterId);
    }

    private String theaterNameOf(Long theaterId) {
        return theaterOf(theaterId).name();
    }

    private Specification<MovieSchedule> filterSpecification(Long movieId, Long theaterId, LocalDate date,
                                                             OffsetDateTime from, OffsetDateTime to) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (movieId != null) {
                predicates.add(builder.equal(root.get("movie").get("id"), movieId));
            }
            if (theaterId != null) {
                predicates.add(builder.equal(root.get("theaterId"), theaterId));
            }
            if (date != null) {
                // "On this date" means the local day in the configured business zone
                // (app.schedule.zone, UTC by default): [00:00, next 00:00).
                OffsetDateTime dayStart = date.atStartOfDay(zone).toOffsetDateTime();
                OffsetDateTime dayEnd = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
                predicates.add(builder.greaterThanOrEqualTo(root.get("startTime"), dayStart));
                predicates.add(builder.lessThan(root.get("startTime"), dayEnd));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("startTime"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThan(root.get("startTime"), to));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Error messages always name an instant in UTC, so they do not depend on the server offset. */
    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }

    private static Pageable toPageable(Integer page, Integer size, List<String> sort) {
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(pageNumber, pageSize, toSort(sort));
    }

    private static Sort toSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return defaultSort();
        }
        List<Sort.Order> orders = new ArrayList<>();
        // Spring already splits "sort=startTime,desc" into two request values, so a direction token
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
        return orders.isEmpty() ? defaultSort() : Sort.by(orders);
    }

    /** Showtimes are browsed chronologically; id breaks ties so paging stays stable. */
    private static Sort defaultSort() {
        return Sort.by(Sort.Order.asc("startTime"), Sort.Order.asc("id"));
    }

    private static boolean isDirection(String value) {
        return "asc".equalsIgnoreCase(value) || "desc".equalsIgnoreCase(value);
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}

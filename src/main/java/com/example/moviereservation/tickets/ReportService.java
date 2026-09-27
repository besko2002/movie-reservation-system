package com.example.moviereservation.tickets;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.PageResponse;
import com.example.moviereservation.common.ResourceNotFoundException;
import com.example.moviereservation.movies.ScheduleDto;
import com.example.moviereservation.movies.ScheduleService;
import com.example.moviereservation.seatreservation.SeatReservationService;
import com.example.moviereservation.theaters.TheaterService;
import jakarta.persistence.criteria.Predicate;
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
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Read-only admin reporting of the tickets module: revenue of a window, occupancy of a showtime and
 * the unfiltered ticket list. Nothing here ever writes a row.
 *
 * <h2>The revenue definition (one place, used by every number below)</h2>
 * Revenue is the sum of {@code tickets.total_price} over the tickets whose status is
 * {@link TicketStatus#PAID} and whose {@code paid_at} lies in {@code [from, to)} — money that
 * actually arrived inside the window, measured at the instant it arrived rather than at the instant
 * the showtime plays.
 *
 * <p>A refund flips the ticket to {@link TicketStatus#REFUNDED}, so it drops out of that sum by
 * itself; refunds are reported next to it ({@code refundedTickets}, {@code refundedAmount}) using
 * the same {@code paid_at} window, and {@code netRevenue} is therefore equal to
 * {@code grossRevenue} — see {@link RevenueReportDto} for why subtracting refunds again would be
 * double counting. {@code PENDING_PAYMENT}, {@code CANCELLED} and {@code EXPIRED} tickets carry no
 * money and are ignored everywhere.
 *
 * <p>All money is {@link BigDecimal} at scale 2, like the {@code NUMERIC(10,2)} columns it comes
 * from; percentages are scale 2 as well.
 *
 * <h2>Module boundaries</h2>
 * Only {@code tickets} is queried directly (aggregated in the database by
 * {@link TicketRepository}). Seat counts come from {@link SeatReservationService}, the capacity of a
 * hall from {@link TheaterService#getSeatCount(Long)}, movie titles, theater names and showtimes
 * from {@link ScheduleService}. No other module's repository or entity is touched.
 */
@Service
public class ReportService {

    /** Largest page a client may ask for on {@code GET /api/admin/tickets}; bigger values clamp. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Page size used when the client does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Width of the default revenue window when {@code from} is not given. */
    public static final int DEFAULT_WINDOW_DAYS = 30;

    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final SeatReservationService seatReservationService;
    private final ScheduleService scheduleService;
    private final TheaterService theaterService;

    private final String currency;

    public ReportService(TicketRepository ticketRepository,
                         TicketService ticketService,
                         SeatReservationService seatReservationService,
                         ScheduleService scheduleService,
                         TheaterService theaterService,
                         @Value("${app.stripe.currency}") String currency) {
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.seatReservationService = seatReservationService;
        this.scheduleService = scheduleService;
        this.theaterService = theaterService;
        this.currency = currency.toLowerCase();
    }

    // --- revenue -------------------------------------------------------------------------------

    /**
     * Revenue of {@code [from, to)} plus its per-movie and per-day breakdowns.
     *
     * @param from inclusive start; {@code null} means {@value #DEFAULT_WINDOW_DAYS} days before the
     *             (effective) end, so a report is never unbounded
     * @param to   exclusive end; {@code null} means now
     * @return the report, with every amount at scale 2
     * @throws BadRequestException when {@code from} is after {@code to} (an empty window,
     *                             {@code from == to}, is accepted and reports zeros)
     */
    @Transactional(readOnly = true)
    public RevenueReportDto revenue(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime windowEnd = to != null ? to : OffsetDateTime.now();
        // Defaulted relative to the window's end, not to "now": asking only for an old `to` then
        // still yields a 30 day window instead of a from > to rejection.
        OffsetDateTime windowStart = from != null ? from : windowEnd.minusDays(DEFAULT_WINDOW_DAYS);
        if (windowStart.isAfter(windowEnd)) {
            throw new BadRequestException(
                    "from (%s) must not be after to (%s)".formatted(format(windowStart), format(windowEnd)));
        }

        RevenueTotals paid = ticketRepository.totalsForStatusInPaidWindow(
                TicketStatus.PAID, windowStart, windowEnd);
        RevenueTotals refunded = ticketRepository.totalsForStatusInPaidWindow(
                TicketStatus.REFUNDED, windowStart, windowEnd);
        BigDecimal gross = money(paid.amountOrZero());

        return new RevenueReportDto(
                windowStart,
                windowEnd,
                currency,
                paid.ticketCount(),
                gross,
                refunded.ticketCount(),
                money(refunded.amountOrZero()),
                // netRevenue == grossRevenue: refunded tickets are not PAID anymore, so they were
                // never part of `gross` and must not be subtracted a second time.
                gross,
                revenueByMovie(windowStart, windowEnd),
                revenueByDay(windowStart, windowEnd));
    }

    /**
     * Turns the per-schedule aggregate of the database into a per-movie breakdown, richest first.
     * Schedules are resolved through {@link ScheduleService}; a schedule that disappeared between
     * the aggregate and the lookup is skipped rather than failing the whole report.
     */
    private List<MovieRevenueDto> revenueByMovie(OffsetDateTime from, OffsetDateTime to) {
        Map<Long, String> titles = new LinkedHashMap<>();
        Map<Long, long[]> counts = new LinkedHashMap<>();
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();

        for (ScheduleRevenueRow row : ticketRepository.revenueByScheduleInPaidWindow(
                TicketStatus.PAID, from, to)) {
            ScheduleDto schedule;
            try {
                schedule = scheduleService.getScheduleById(row.scheduleId());
            } catch (ResourceNotFoundException ex) {
                continue;
            }
            Long movieId = schedule.movie().id();
            titles.putIfAbsent(movieId, schedule.movie().title());
            counts.computeIfAbsent(movieId, key -> new long[1])[0] += row.ticketCount();
            amounts.merge(movieId, row.revenueOrZero(), BigDecimal::add);
        }

        List<MovieRevenueDto> byMovie = new ArrayList<>(titles.size());
        titles.forEach((movieId, title) -> byMovie.add(new MovieRevenueDto(
                movieId, title, counts.get(movieId)[0], money(amounts.get(movieId)))));
        byMovie.sort(Comparator.comparing(MovieRevenueDto::revenue).reversed()
                .thenComparing(MovieRevenueDto::movieId));
        return List.copyOf(byMovie);
    }

    /**
     * The daily series, grouped by the database in the business zone {@code app.schedule.zone}
     * (the same zone {@code GET /api/schedules?date=} uses), oldest day first.
     */
    private List<DailyRevenueDto> revenueByDay(OffsetDateTime from, OffsetDateTime to) {
        ZoneId zone = scheduleService.getZone();
        return ticketRepository.revenueByDayInPaidWindow(
                        TicketStatus.PAID.name(), zone.getId(), from, to).stream()
                .map(row -> new DailyRevenueDto(toLocalDate(row[0]), toLong(row[1]), money(toBigDecimal(row[2]))))
                .toList();
    }

    // --- occupancy -----------------------------------------------------------------------------

    /**
     * How full one showtime is.
     *
     * @param scheduleId showtime to report on
     * @return capacity, booked/held/available seats, the occupancy percentage and the money taken
     * @throws ResourceNotFoundException when no schedule has that id
     */
    @Transactional(readOnly = true)
    public OccupancyReportDto occupancy(Long scheduleId) {
        ScheduleDto schedule = scheduleService.getScheduleById(scheduleId);
        long capacity = theaterService.getSeatCount(schedule.theater().id());
        long booked = seatReservationService.countConfirmedForSchedule(scheduleId);
        long held = seatReservationService.countLiveHeldForSchedule(scheduleId);
        // Never negative: a hall whose grid was regenerated could in theory hold fewer seats than
        // the rows that still point at it.
        long available = Math.max(0L, capacity - booked - held);

        return new OccupancyReportDto(
                schedule.id(),
                schedule.movie().title(),
                schedule.theater().name(),
                schedule.startTime(),
                capacity,
                booked,
                held,
                available,
                percentOf(booked, capacity),
                money(nullToZero(ticketRepository.sumTotalPriceForScheduleAndStatus(
                        scheduleId, TicketStatus.PAID))));
    }

    // --- admin ticket list ---------------------------------------------------------------------

    /**
     * Every ticket in the system, newest first, with optional filters — the admin counterpart of
     * {@code GET /api/tickets/me}.
     *
     * @param status     only that status, case insensitive; {@code null} or blank for no filter
     * @param scheduleId only tickets of that showtime; {@code null} for no filter
     * @param userId     only tickets of that buyer; {@code null} for no filter
     * @param page       zero-based page index; negative values are treated as 0
     * @param size       page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     * @return a page envelope of full {@link TicketDto}s, each with its seats
     * @throws BadRequestException when {@code status} is not a known {@link TicketStatus}
     */
    @Transactional(readOnly = true)
    public PageResponse<TicketDto> listAllTickets(String status, Long scheduleId, Long userId,
                                                  Integer page, Integer size) {
        TicketStatus parsedStatus = parseStatus(status);
        Pageable pageable = PageRequest.of(
                page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE),
                // id desc == newest first: ids are generated in creation order.
                Sort.by(Sort.Order.desc("id")));

        Page<Ticket> tickets = ticketRepository.findAll(filterSpecification(parsedStatus, scheduleId, userId), pageable);
        List<TicketDto> content = tickets.getContent().stream()
                .map(ticketService::toDto)
                .toList();
        return PageResponse.of(tickets, content);
    }

    private static Specification<Ticket> filterSpecification(TicketStatus status, Long scheduleId, Long userId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (scheduleId != null) {
                predicates.add(builder.equal(root.get("scheduleId"), scheduleId));
            }
            if (userId != null) {
                predicates.add(builder.equal(root.get("userId"), userId));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** An unknown status is a client mistake (400), never an empty page that hides a typo. */
    private static TicketStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown ticket status '%s'; allowed: %s".formatted(
                    status, Stream.of(TicketStatus.values()).map(Enum::name).toList()));
        }
    }

    // --- internals -----------------------------------------------------------------------------

    private static BigDecimal percentOf(long part, long total) {
        if (total <= 0) {
            return money(BigDecimal.ZERO);
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return nullToZero(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** The JDBC driver may hand a {@code date} column back as {@link Date} or as {@link LocalDate}. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : BigDecimal.ZERO;
    }

    private static String format(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }
}

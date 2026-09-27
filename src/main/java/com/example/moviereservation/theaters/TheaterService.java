package com.example.moviereservation.theaters;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.PageResponse;
import com.example.moviereservation.common.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Public API of the theaters module: theater administration and the seat grid it owns.
 *
 * <p>Other modules must depend on this service, never on {@code TheaterRepository} or
 * {@code SeatRepository}. Later phases (schedules, seat reservations) use
 * {@link #getTheaterById(Long)}, {@link #theaterExists(Long)}, {@link #listSeats(Long)} and
 * {@link #getSeatCount(Long)}.
 */
@Service
public class TheaterService {

    private static final Logger log = LoggerFactory.getLogger(TheaterService.class);

    /** Largest page a client may ask for; bigger values are clamped down to it. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Page size used when the client does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Sortable properties; anything else is rejected instead of leaking a 500. */
    private static final Set<String> SORTABLE = Set.of(
            "id", "name", "location", "totalRows", "seatsPerRow", "createdAt");

    private final TheaterRepository theaterRepository;
    private final SeatRepository seatRepository;

    /**
     * Regeneration seam: empty in phase 4, so every grid change is allowed. As soon as a later
     * phase publishes a {@link SeatUsagePolicy} bean (seats referenced by reservations/tickets),
     * it is consulted here without any other change to this service.
     */
    private final ObjectProvider<SeatUsagePolicy> seatUsagePolicy;

    /**
     * Deletion seam: consulted before a theater is deleted. From phase 5 on the movies module
     * publishes a {@link TheaterUsagePolicy} bean that refuses to delete a theater which still has
     * schedules; the theaters module itself never learns what a schedule is.
     */
    private final ObjectProvider<TheaterUsagePolicy> theaterUsagePolicy;

    public TheaterService(TheaterRepository theaterRepository,
                          SeatRepository seatRepository,
                          ObjectProvider<SeatUsagePolicy> seatUsagePolicy,
                          ObjectProvider<TheaterUsagePolicy> theaterUsagePolicy) {
        this.theaterRepository = theaterRepository;
        this.seatRepository = seatRepository;
        this.seatUsagePolicy = seatUsagePolicy;
        this.theaterUsagePolicy = theaterUsagePolicy;
    }

    /**
     * Lists theaters.
     *
     * @param page zero-based page index; negative values are treated as 0
     * @param size page size, clamped to 1..{@value #MAX_PAGE_SIZE}
     * @param sort {@code property[,asc|desc]} entries
     * @return a stable page envelope of {@link TheaterSummaryDto}
     * @throws BadRequestException when a sort property is unknown
     */
    @Transactional(readOnly = true)
    public PageResponse<TheaterSummaryDto> listTheaters(Integer page, Integer size, List<String> sort) {
        Pageable pageable = toPageable(page, size, sort);
        Page<Theater> theaters = theaterRepository.findAll(pageable);
        return PageResponse.of(theaters, theaters.getContent().stream().map(TheaterSummaryDto::from).toList());
    }

    /**
     * @return the theater with its seat counts
     * @throws ResourceNotFoundException when no theater has that id
     */
    @Transactional(readOnly = true)
    public TheaterDto getTheaterById(Long id) {
        return theaterRepository.findWithSeatsById(id)
                .map(TheaterDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Theater", id));
    }

    /** @return whether a theater with that id exists (used by the schedules module) */
    @Transactional(readOnly = true)
    public boolean theaterExists(Long id) {
        return id != null && theaterRepository.existsById(id);
    }

    /**
     * @return every seat of the theater, ordered by row label then seat number
     * @throws ResourceNotFoundException when no theater has that id
     */
    @Transactional(readOnly = true)
    public List<SeatDto> listSeats(Long id) {
        requireTheater(id);
        return seatRepository.findByTheater_IdOrderByRowLabelAscSeatNumberAsc(id).stream()
                .map(SeatDto::from)
                .toList();
    }

    /**
     * @return how many seats the theater has (capacity for occupancy reports)
     * @throws ResourceNotFoundException when no theater has that id
     */
    @Transactional(readOnly = true)
    public long getSeatCount(Long id) {
        requireTheater(id);
        return seatRepository.countByTheater_Id(id);
    }

    /**
     * Creates a theater and generates all of its seats.
     *
     * @throws ConflictException   when a theater with that name already exists, ignoring case
     * @throws BadRequestException when a VIP row is not part of the generated grid
     */
    @Transactional
    public TheaterDto createTheater(CreateTheaterRequest request) {
        String name = request.name();
        if (theaterRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException(duplicateMessage(name));
        }
        SeatLayout layout = SeatLayout.of(request.totalRows(), request.seatsPerRow(), request.vipRows());
        Theater theater = new Theater(name, request.location(), layout.totalRows(), layout.seatsPerRow());
        theater.applyLayout(layout);
        try {
            theater = theaterRepository.saveAndFlush(theater);
        } catch (DataIntegrityViolationException ex) {
            // The unique index is the final arbiter when two creations race.
            throw new ConflictException(duplicateMessage(name));
        }
        log.info("Created theater id={} grid={}x{} seats={} vipRows={}",
                theater.getId(), layout.totalRows(), layout.seatsPerRow(),
                theater.getSeats().size(), layout.vipRows());
        return TheaterDto.from(theater);
    }

    /**
     * Full update. The seats are regenerated only when the grid actually changes; renaming or
     * moving a theater keeps the existing seat ids, which later phases rely on because seat
     * reservations point at them.
     *
     * @throws ResourceNotFoundException when no theater has that id
     * @throws ConflictException         when the name is taken, or when a {@link SeatUsagePolicy}
     *                                   refuses to let the seats be regenerated
     * @throws BadRequestException       when a VIP row is not part of the grid
     */
    @Transactional
    public TheaterDto updateTheater(Long id, UpdateTheaterRequest request) {
        Theater theater = theaterRepository.findWithSeatsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Theater", id));
        String name = request.name();
        Optional<Theater> clash = theaterRepository.findByNameIgnoreCase(name);
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            throw new ConflictException(duplicateMessage(name));
        }
        theater.setName(name);
        theater.setLocation(request.location());

        SeatLayout requestedLayout = SeatLayout.of(request.totalRows(), request.seatsPerRow(), request.vipRows());
        if (!requestedLayout.equals(theater.currentLayout())) {
            // --- seat regeneration seam -------------------------------------------------------
            // Only a real grid change gets here, and only after the policy allows it.
            seatUsagePolicy.ifAvailable(policy -> policy.checkSeatsMayBeRegenerated(id));
            theater.clearSeats();
            // The old seats must be gone in the database before the new ones are inserted, because
            // row labels and seat numbers are reused and are unique per theater.
            theaterRepository.flush();
            theater.applyLayout(requestedLayout);
            log.info("Regenerated seats of theater id={} grid={}x{} vipRows={}",
                    id, requestedLayout.totalRows(), requestedLayout.seatsPerRow(), requestedLayout.vipRows());
        }
        try {
            theaterRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(duplicateMessage(name));
        }
        return TheaterDto.from(theater);
    }

    /**
     * Deletes a theater; its seats go with it.
     *
     * @throws ResourceNotFoundException when no theater has that id
     * @throws ConflictException         when a {@link TheaterUsagePolicy} refuses the deletion
     *                                   (the theater still has schedules), or when a foreign key
     *                                   from another module still points at the theater
     */
    @Transactional
    public void deleteTheater(Long id) {
        Theater theater = theaterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Theater", id));
        // --- deletion seam ---------------------------------------------------------------------
        // The normal path: ask every module that references theaters by id (phase 5: schedules).
        theaterUsagePolicy.ifAvailable(policy -> policy.checkTheaterMayBeDeleted(id));
        // Seats are owned by the theater (cascade + orphanRemoval, plus ON DELETE CASCADE).
        theaterRepository.delete(theater);
        try {
            theaterRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // Safety net only: an ON DELETE RESTRICT reference the policy did not know about (or a
            // row inserted concurrently) must still be a 409, never a 500.
            throw new ConflictException(
                    "Theater %d is still referenced and cannot be deleted".formatted(id));
        }
        log.info("Deleted theater id={}", id);
    }

    private void requireTheater(Long id) {
        if (id == null || !theaterRepository.existsById(id)) {
            throw new ResourceNotFoundException("Theater", id);
        }
    }

    private static String duplicateMessage(String name) {
        return "Theater '%s' already exists".formatted(name);
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
        // Spring already splits "sort=name,desc" into two request values, so a direction token
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
}

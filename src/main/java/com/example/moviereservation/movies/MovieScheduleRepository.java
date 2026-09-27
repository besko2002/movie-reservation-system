package com.example.moviereservation.movies;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Module-internal persistence for {@link MovieSchedule}; other modules go through
 * {@link ScheduleService}.
 */
interface MovieScheduleRepository extends JpaRepository<MovieSchedule, Long>,
        JpaSpecificationExecutor<MovieSchedule> {

    /** Loads a schedule together with its movie, so the detail DTO needs no second query. */
    @EntityGraph(attributePaths = "movie")
    Optional<MovieSchedule> findWithMovieById(Long id);

    /**
     * Schedules of the same theater whose window overlaps {@code [start, end)}.
     *
     * <p>Half-open comparison: {@code start < existing.endTime AND end > existing.startTime}, so
     * back-to-back showtimes that merely touch are not a conflict.
     *
     * @param excludeId id to ignore, so an update never collides with itself; pass a negative id
     *                  when creating
     */
    @Query("""
            select s from MovieSchedule s
            where s.theaterId = :theaterId
              and s.id <> :excludeId
              and s.startTime < :end
              and s.endTime > :start
            order by s.startTime asc""")
    List<MovieSchedule> findOverlapping(@Param("theaterId") Long theaterId,
                                        @Param("start") OffsetDateTime start,
                                        @Param("end") OffsetDateTime end,
                                        @Param("excludeId") Long excludeId);

    /** Every schedule of the movie; used to recompute end times when its duration changes. */
    List<MovieSchedule> findByMovie_Id(Long movieId);

    /** How many schedules still reference the movie; used when guarding movie deletion. */
    long countByMovie_Id(Long movieId);

    /** How many schedules still reference the theater; used when guarding theater deletion. */
    long countByTheaterId(Long theaterId);
}

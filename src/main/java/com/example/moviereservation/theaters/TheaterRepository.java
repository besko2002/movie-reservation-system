package com.example.moviereservation.theaters;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Module-internal persistence for {@link Theater}; other modules go through {@link TheaterService}. */
interface TheaterRepository extends JpaRepository<Theater, Long> {

    /** Loads a theater together with its seats, for reads and for seat regeneration. */
    @EntityGraph(attributePaths = "seats")
    Optional<Theater> findWithSeatsById(Long id);

    boolean existsByNameIgnoreCase(String name);

    Optional<Theater> findByNameIgnoreCase(String name);
}

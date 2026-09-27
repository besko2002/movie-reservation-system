package com.example.moviereservation.movies;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Module-internal persistence for {@link Genre}; other modules go through {@link GenreService}. */
interface GenreRepository extends JpaRepository<Genre, Long> {

    List<Genre> findAllByOrderByNameAsc();

    Optional<Genre> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}

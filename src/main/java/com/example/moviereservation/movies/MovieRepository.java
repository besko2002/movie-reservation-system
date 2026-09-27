package com.example.moviereservation.movies;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** Module-internal persistence for {@link Movie}; other modules go through {@link MovieService}. */
interface MovieRepository extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

    /** Loads a movie together with its genres, avoiding a second query per movie. */
    @EntityGraph(attributePaths = "genres")
    Optional<Movie> findWithGenresById(Long id);

    /** How many movies still reference the genre; used to guard genre deletion. */
    long countByGenres_Id(Long genreId);
}

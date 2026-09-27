package com.example.moviereservation.movies;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Public API of the genres part of the movies module.
 *
 * <p>Other modules must depend on this service, never on {@code GenreRepository}.
 */
@Service
public class GenreService {

    private static final Logger log = LoggerFactory.getLogger(GenreService.class);

    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;

    public GenreService(GenreRepository genreRepository, MovieRepository movieRepository) {
        this.genreRepository = genreRepository;
        this.movieRepository = movieRepository;
    }

    /** @return every genre, sorted by name */
    @Transactional(readOnly = true)
    public List<GenreDto> getAllGenres() {
        return genreRepository.findAllByOrderByNameAsc().stream().map(GenreDto::from).toList();
    }

    /** @throws ResourceNotFoundException when no genre has that id */
    @Transactional(readOnly = true)
    public GenreDto getGenreById(Long id) {
        return GenreDto.from(findOrThrow(id));
    }

    /**
     * Creates a genre with a trimmed name.
     *
     * @throws ConflictException when a genre with that name already exists, ignoring case
     */
    @Transactional
    public GenreDto createGenre(GenreRequest request) {
        String name = request.name();
        if (genreRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException(duplicateMessage(name));
        }
        Genre genre = new Genre(name);
        try {
            genre = genreRepository.saveAndFlush(genre);
        } catch (DataIntegrityViolationException ex) {
            // The unique index is the final arbiter when two creations race.
            throw new ConflictException(duplicateMessage(name));
        }
        log.info("Created genre id={} name={}", genre.getId(), genre.getName());
        return GenreDto.from(genre);
    }

    /**
     * Renames a genre.
     *
     * @throws ResourceNotFoundException when no genre has that id
     * @throws ConflictException         when another genre already uses that name, ignoring case
     */
    @Transactional
    public GenreDto updateGenre(Long id, GenreRequest request) {
        Genre genre = findOrThrow(id);
        String name = request.name();
        Optional<Genre> clash = genreRepository.findByNameIgnoreCase(name);
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            throw new ConflictException(duplicateMessage(name));
        }
        genre.setName(name);
        try {
            genreRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(duplicateMessage(name));
        }
        return GenreDto.from(genre);
    }

    /**
     * Deletes a genre.
     *
     * @throws ResourceNotFoundException when no genre has that id
     * @throws ConflictException         when the genre is still attached to at least one movie
     */
    @Transactional
    public void deleteGenre(Long id) {
        Genre genre = findOrThrow(id);
        long usage = movieRepository.countByGenres_Id(id);
        if (usage > 0) {
            throw new ConflictException(
                    "Genre '%s' is still used by %d movie(s) and cannot be deleted".formatted(genre.getName(), usage));
        }
        genreRepository.delete(genre);
        log.info("Deleted genre id={}", id);
    }

    /**
     * Resolves genre ids into entities for the movies side of the module.
     *
     * @throws BadRequestException when at least one id does not exist
     */
    Set<Genre> resolveGenres(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        List<Genre> found = genreRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            Set<Long> foundIds = found.stream().map(Genre::getId).collect(Collectors.toSet());
            List<Long> unknown = ids.stream().filter(id -> !foundIds.contains(id)).sorted().toList();
            throw new BadRequestException("Unknown genre id(s): " + unknown);
        }
        return new LinkedHashSet<>(found);
    }

    private Genre findOrThrow(Long id) {
        return genreRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Genre", id));
    }

    private static String duplicateMessage(String name) {
        return "Genre '%s' already exists".formatted(name);
    }
}

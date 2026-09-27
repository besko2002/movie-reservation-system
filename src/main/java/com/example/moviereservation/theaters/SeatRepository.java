package com.example.moviereservation.theaters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Module-internal persistence for {@link Seat}; other modules go through {@link TheaterService}. */
interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Seat map of a theater, ordered by row label then seat number. */
    List<Seat> findByTheater_IdOrderByRowLabelAscSeatNumberAsc(Long theaterId);

    long countByTheater_Id(Long theaterId);

    long countByTheater_IdAndType(Long theaterId, SeatType type);
}

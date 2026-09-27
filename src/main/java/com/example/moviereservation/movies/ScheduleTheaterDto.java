package com.example.moviereservation.movies;

import com.example.moviereservation.theaters.TheaterDto;

/**
 * Compact theater view embedded in a schedule response.
 *
 * <p>Built from the theaters module's public {@code TheaterDto}: the schedules code never touches
 * the {@code Theater} entity or its repository.
 *
 * @param id         theater id
 * @param name       theater name
 * @param location   where the theater is, may be {@code null}
 * @param totalSeats how many seats the theater has
 */
public record ScheduleTheaterDto(
        Long id,
        String name,
        String location,
        long totalSeats
) {

    static ScheduleTheaterDto from(TheaterDto theater) {
        return new ScheduleTheaterDto(theater.id(), theater.name(), theater.location(), theater.totalSeats());
    }
}

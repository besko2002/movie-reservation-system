package com.example.moviereservation.theaters;

/**
 * Compact theater view used in list responses.
 *
 * @param id          theater id
 * @param name        theater name, unique
 * @param location    where the theater is, may be {@code null}
 * @param totalRows   number of rows
 * @param seatsPerRow seats in each row
 * @param totalSeats  number of seats, {@code totalRows * seatsPerRow}
 */
public record TheaterSummaryDto(
        Long id,
        String name,
        String location,
        int totalRows,
        int seatsPerRow,
        int totalSeats
) {

    /**
     * Derives the summary without touching the lazy seat collection: the grid always holds exactly
     * {@code totalRows * seatsPerRow} seats because seats are only ever generated from it.
     */
    static TheaterSummaryDto from(Theater theater) {
        return new TheaterSummaryDto(
                theater.getId(),
                theater.getName(),
                theater.getLocation(),
                theater.getTotalRows(),
                theater.getSeatsPerRow(),
                theater.getTotalRows() * theater.getSeatsPerRow());
    }
}

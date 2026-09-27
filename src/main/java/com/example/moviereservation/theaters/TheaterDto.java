package com.example.moviereservation.theaters;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full theater view, including the counts of its generated seats.
 *
 * @param id          theater id
 * @param name        theater name, unique
 * @param location    where the theater is, may be {@code null}
 * @param totalRows   number of rows
 * @param seatsPerRow seats in each row
 * @param totalSeats  number of seats the theater has
 * @param vipSeats    number of VIP seats
 * @param normalSeats number of NORMAL seats
 * @param vipRows     row labels that are VIP, sorted
 * @param createdAt   when the theater was created
 */
public record TheaterDto(
        Long id,
        String name,
        String location,
        int totalRows,
        int seatsPerRow,
        long totalSeats,
        long vipSeats,
        long normalSeats,
        List<String> vipRows,
        OffsetDateTime createdAt
) {

    /** Must be called while the theater is attached, because its seats are lazy. */
    static TheaterDto from(Theater theater) {
        long vipSeats = theater.getSeats().stream().filter(seat -> seat.getType() == SeatType.VIP).count();
        long totalSeats = theater.getSeats().size();
        return new TheaterDto(
                theater.getId(),
                theater.getName(),
                theater.getLocation(),
                theater.getTotalRows(),
                theater.getSeatsPerRow(),
                totalSeats,
                vipSeats,
                totalSeats - vipSeats,
                theater.currentLayout().vipRows(),
                theater.getCreatedAt());
    }
}

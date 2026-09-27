package com.example.moviereservation.movies;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Full schedule (showtime) view: the movie that plays, the theater it plays in, the window it
 * occupies and the seat prices.
 *
 * <p>{@code endTime} is computed by the server as
 * {@code startTime + movie.durationMinutes + cleaning buffer} and can never be sent by a client.
 *
 * @param id              schedule id
 * @param movie           the movie that plays
 * @param theater         the theater it plays in
 * @param startTime       when the showtime starts (instant, serialised with its offset)
 * @param endTime         when the theater is free again, cleaning buffer included
 * @param durationMinutes running time of the movie, without the buffer
 * @param bufferMinutes   cleaning buffer included in {@code endTime}
 * @param prices          prices charged for NORMAL and VIP seats
 * @param createdAt       when the schedule was created
 */
public record ScheduleDto(
        Long id,
        MovieSummaryDto movie,
        ScheduleTheaterDto theater,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        int durationMinutes,
        int bufferMinutes,
        SchedulePricesDto prices,
        OffsetDateTime createdAt
) {

    /** Convenience accessor for the NORMAL seat price. */
    public BigDecimal basePrice() {
        return prices.basePrice();
    }

    /** Convenience accessor for the VIP seat price. */
    public BigDecimal vipPrice() {
        return prices.vipPrice();
    }

    /** Must be called while the schedule is attached, because its movie is lazy. */
    static ScheduleDto from(MovieSchedule schedule, ScheduleTheaterDto theater, int bufferMinutes) {
        Movie movie = schedule.getMovie();
        return new ScheduleDto(
                schedule.getId(),
                MovieSummaryDto.from(movie),
                theater,
                schedule.getStartTime(),
                schedule.getEndTime(),
                movie.getDurationMinutes(),
                bufferMinutes,
                SchedulePricesDto.from(schedule),
                schedule.getCreatedAt());
    }
}

package com.example.moviereservation.movies;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Compact schedule view used in list responses: flat ids and names instead of nested objects.
 *
 * @param id          schedule id
 * @param movieId     id of the movie that plays
 * @param movieTitle  title of that movie
 * @param theaterId   id of the theater it plays in
 * @param theaterName name of that theater
 * @param startTime   when the showtime starts
 * @param endTime     when the theater is free again, cleaning buffer included
 * @param basePrice   price of a NORMAL seat
 * @param vipPrice    price of a VIP seat
 */
public record ScheduleSummaryDto(
        Long id,
        Long movieId,
        String movieTitle,
        Long theaterId,
        String theaterName,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        BigDecimal basePrice,
        BigDecimal vipPrice
) {

    /** Must be called while the schedule is attached, because its movie is lazy. */
    static ScheduleSummaryDto from(MovieSchedule schedule, String theaterName) {
        Movie movie = schedule.getMovie();
        return new ScheduleSummaryDto(
                schedule.getId(),
                movie.getId(),
                movie.getTitle(),
                schedule.getTheaterId(),
                theaterName,
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getBasePrice(),
                schedule.getVipPrice());
    }
}

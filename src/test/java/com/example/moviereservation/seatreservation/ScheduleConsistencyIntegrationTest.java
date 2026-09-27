package com.example.moviereservation.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Schedules must stay consistent with what depends on them: the reservations made for them and the
 * duration of the movie they play.
 */
class ScheduleConsistencyIntegrationTest extends AbstractSeatReservationIntegrationTest {

    @Value("${app.schedule.buffer-minutes}")
    private int bufferMinutes;

    @Test
    void scheduleWithHeldSeatsCannotBeMovedButPricesCanChange() throws Exception {
        String admin = adminToken();
        OffsetDateTime start = futureStart().plusDays(300);
        Show show = createShow(admin, start);
        TestUser user = registerUser();
        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isCreated());

        long otherTheater = createTheater(admin, 2, 3);
        mockMvc.perform(put("/api/schedules/" + show.scheduleId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(show.movieId(), otherTheater, start, "10.00", "25.00")))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/schedules/" + show.scheduleId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(show.movieId(), show.theaterId(), start.plusHours(3), "10.00", "25.00")))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/schedules/" + show.scheduleId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(show.movieId(), show.theaterId(), start, "11.00", "26.00")))
                .andExpect(status().isOk());
    }

    @Test
    void changingMovieDurationRecomputesScheduleEndTimes() throws Exception {
        String admin = adminToken();
        OffsetDateTime start = futureStart().plusDays(310);
        Show show = createShow(admin, start);

        mockMvc.perform(put("/api/movies/" + show.movieId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movieBody(uniqueName("Longer Cut"), 180)))
                .andExpect(status().isOk());

        assertThat(endTimeOf(show.scheduleId()))
                .isEqualTo(start.plusMinutes(180 + bufferMinutes).toInstant());
    }

    @Test
    void longerDurationThatWouldOverlapTheNextShowIsRejected() throws Exception {
        String admin = adminToken();
        OffsetDateTime start = futureStart().plusDays(320);
        Show show = createShow(admin, start);
        // Back-to-back: the second show starts exactly when the first one's window ends.
        OffsetDateTime secondStart = start.plusMinutes(DURATION_MINUTES + bufferMinutes);
        long otherMovie = createMovie(admin);
        createSchedule(admin, otherMovie, show.theaterId(), secondStart);

        mockMvc.perform(put("/api/movies/" + show.movieId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movieBody(uniqueName("Director's Cut"), DURATION_MINUTES + 30)))
                .andExpect(status().isConflict());

        assertThat(endTimeOf(show.scheduleId())).isEqualTo(secondStart.toInstant());
        mockMvc.perform(get("/api/movies/" + show.movieId()))
                .andExpect(jsonPath("$.durationMinutes").value(DURATION_MINUTES));
    }

    @Test
    void titleSearchTreatsLikeWildcardsLiterally() throws Exception {
        String admin = adminToken();
        String suffix = uniqueName("wild");
        for (String title : List.of("A_B " + suffix, "AxB " + suffix, "100% " + suffix, "1000 " + suffix)) {
            mockMvc.perform(post("/api/movies")
                            .header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(movieBody(title, 90)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/movies").param("title", "A_B " + suffix))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/movies").param("title", "100% " + suffix))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private java.time.Instant endTimeOf(long scheduleId) throws Exception {
        String body = mockMvc.perform(get("/api/schedules/" + scheduleId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return OffsetDateTime.parse(node.path("endTime").asText()).toInstant();
    }

    private static String movieBody(String title, int durationMinutes) {
        return """
                {"title":"%s","durationMinutes":%d,"genreIds":[]}""".formatted(title, durationMinutes);
    }

    private static String scheduleBody(long movieId, long theaterId, OffsetDateTime start,
                                       String basePrice, String vipPrice) {
        return """
                {"movieId":%d,"theaterId":%d,"startTime":"%s","basePrice":%s,"vipPrice":%s}"""
                .formatted(movieId, theaterId, iso(start), basePrice, vipPrice);
    }
}

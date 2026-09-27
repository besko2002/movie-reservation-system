package com.example.moviereservation.movies;

import com.example.moviereservation.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the schedules part of the movies module (phase 5) against a real
 * PostgreSQL started by {@link TestcontainersConfiguration}.
 *
 * <p>Isolation inside the shared database comes from fixtures: every test creates its own theater,
 * so the per-theater overlap rule can never be disturbed by another test, and every date filter is
 * combined with a theater id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SchedulesIntegrationTest {

    private static final String USER_PASSWORD = "Sup3rSecret!";
    private static final int DURATION_MINUTES = 120;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Value("${app.schedule.buffer-minutes}")
    private int bufferMinutes;

    // --- creation ------------------------------------------------------------------------------

    @Test
    void createComputesEndTimeFromDurationAndBuffer() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        OffsetDateTime start = futureStart(1);

        String body = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start, "10.00", "15.50")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/schedules/")))
                .andExpect(jsonPath("$.movie.id").value(movieId))
                .andExpect(jsonPath("$.theater.id").value(theaterId))
                .andExpect(jsonPath("$.durationMinutes").value(DURATION_MINUTES))
                .andExpect(jsonPath("$.bufferMinutes").value(bufferMinutes))
                .andReturn().getResponse().getContentAsString();

        JsonNode schedule = objectMapper.readTree(body);
        assertThat(instant(schedule, "startTime")).isEqualTo(start.toInstant());
        assertThat(instant(schedule, "endTime"))
                .isEqualTo(start.plusMinutes(DURATION_MINUTES + bufferMinutes).toInstant());
        assertThat(new BigDecimal(schedule.path("prices").path("basePrice").asText()))
                .isEqualByComparingTo("10.00");
        assertThat(new BigDecimal(schedule.path("prices").path("vipPrice").asText()))
                .isEqualByComparingTo("15.50");
    }

    @Test
    void overlappingScheduleInTheSameTheaterReturns409() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        OffsetDateTime start = futureStart(2);
        long firstId = createSchedule(token, movieId, theaterId, start);

        // Starts one hour into the running movie.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start.plusHours(1), "10.00", "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("schedule " + firstId)))
                .andExpect(jsonPath("$.message").value(containsString(iso(start))));

        // Starts while the hall is still being cleaned: inside the buffer, so still a conflict.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId,
                                start.plusMinutes(DURATION_MINUTES + bufferMinutes - 1), "10.00", "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("schedule " + firstId)));

        // Exactly back-to-back (touching windows) is allowed.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId,
                                start.plusMinutes(DURATION_MINUTES + bufferMinutes), "10.00", "20.00")))
                .andExpect(status().isCreated());
    }

    @Test
    void sameWindowInADifferentTheaterIsAllowed() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterA = createTheater(token);
        long theaterB = createTheater(token);
        OffsetDateTime start = futureStart(3);

        createSchedule(token, movieId, theaterA, start);

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterB, start, "10.00", "20.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.theater.id").value(theaterB));
    }

    @Test
    void invalidCreateRequestsReturn400() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        OffsetDateTime start = futureStart(4);

        // startTime in the past.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId,
                                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), "10.00", "20.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("startTime must be in the future")));

        // Unknown movie.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(9999999L, theaterId, start, "10.00", "20.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown movieId 9999999")));

        // Unknown theater.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, 9999999L, start, "10.00", "20.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown theaterId 9999999")));

        // VIP cheaper than base.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start, "20.00", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("vipPrice")));

        // Negative price.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start, "-1.00", "20.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.basePrice").value("basePrice must not be negative"));

        // More than two decimals.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start, "10.123", "20.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.basePrice").value("basePrice must have at most 2 decimals"));

        // Nothing of the above was persisted for this theater.
        mockMvc.perform(get("/api/schedules").param("theaterId", String.valueOf(theaterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- reads ---------------------------------------------------------------------------------

    @Test
    void getByIdReturnsNestedMovieAndTheaterAndUnknownIdReturns404() throws Exception {
        String token = adminToken();
        String title = uniqueName("Schedule Movie");
        long movieId = createMovie(token, title, DURATION_MINUTES);
        String theaterName = uniqueName("Schedule Hall");
        long theaterId = createTheater(token, theaterName);
        OffsetDateTime start = futureStart(5);
        long id = createSchedule(token, movieId, theaterId, start);

        mockMvc.perform(get("/api/schedules/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.movie.id").value(movieId))
                .andExpect(jsonPath("$.movie.title").value(title))
                .andExpect(jsonPath("$.movie.durationMinutes").value(DURATION_MINUTES))
                .andExpect(jsonPath("$.theater.id").value(theaterId))
                .andExpect(jsonPath("$.theater.name").value(theaterName))
                .andExpect(jsonPath("$.theater.totalSeats").value(6))
                .andExpect(jsonPath("$.prices.basePrice").exists())
                .andExpect(jsonPath("$.prices.vipPrice").exists())
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/schedules/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void listFiltersByMovieTheaterAndDateAndIsPaginated() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long otherMovieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        long otherTheaterId = createTheater(token);

        OffsetDateTime day1 = futureStart(6).withHour(10);
        OffsetDateTime day2 = day1.plusDays(1);
        createSchedule(token, movieId, theaterId, day1);
        createSchedule(token, movieId, theaterId, day1.plusHours(4));
        createSchedule(token, otherMovieId, theaterId, day2);
        createSchedule(token, otherMovieId, otherTheaterId, day1);

        // By theater: 3 schedules.
        mockMvc.perform(get("/api/schedules").param("theaterId", String.valueOf(theaterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].theaterName").exists())
                .andExpect(jsonPath("$.content[0].movieTitle").exists());

        // By movie (that movie plays in two theaters).
        mockMvc.perform(get("/api/schedules").param("movieId", String.valueOf(otherMovieId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // By movie + theater.
        mockMvc.perform(get("/api/schedules")
                        .param("movieId", String.valueOf(movieId))
                        .param("theaterId", String.valueOf(theaterId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // By date (UTC business zone) within this theater.
        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("date", day1.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("date", day2.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // from/to window.
        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("from", iso(day1.plusHours(1)))
                        .param("to", iso(day2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Default sort is startTime ascending.
        String body = mockMvc.perform(get("/api/schedules").param("theaterId", String.valueOf(theaterId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(body).path("content");
        assertThat(instant(content.get(0), "startTime")).isBefore(instant(content.get(1), "startTime"));
        assertThat(instant(content.get(1), "startTime")).isBefore(instant(content.get(2), "startTime"));

        // Paging and the shared envelope.
        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/api/schedules").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get("/api/schedules").param("sort", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown sort property")));
    }

    // --- update and delete -----------------------------------------------------------------------

    @Test
    void updateMovesTheScheduleAndRechecksOverlap() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        OffsetDateTime start = futureStart(7);
        long firstId = createSchedule(token, movieId, theaterId, start);
        OffsetDateTime laterStart = start.plusHours(6);
        long secondId = createSchedule(token, movieId, theaterId, laterStart);

        // Updating a schedule onto its own window is not a self-collision.
        mockMvc.perform(put("/api/schedules/" + secondId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, laterStart, "12.00", "24.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(secondId));

        // Moving it on top of the first one is refused.
        mockMvc.perform(put("/api/schedules/" + secondId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start.plusMinutes(30), "12.00", "24.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("schedule " + firstId)));

        // A window that fits is accepted, and the end time is recomputed.
        OffsetDateTime movedStart = start.plusHours(10);
        String body = mockMvc.perform(put("/api/schedules/" + secondId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, movedStart, "12.00", "24.00")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode updated = objectMapper.readTree(body);
        assertThat(instant(updated, "startTime")).isEqualTo(movedStart.toInstant());
        assertThat(instant(updated, "endTime"))
                .isEqualTo(movedStart.plusMinutes(DURATION_MINUTES + bufferMinutes).toInstant());

        mockMvc.perform(put("/api/schedules/9999999")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, movedStart, "12.00", "24.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesTheSchedule() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        long id = createSchedule(token, movieId, theaterId, futureStart(8));

        mockMvc.perform(delete("/api/schedules/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/schedules/" + id))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/schedules/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    // --- authorization ---------------------------------------------------------------------------

    @Test
    void readingIsPublicAndWritingRequiresAnAdmin() throws Exception {
        String adminToken = adminToken();
        String userToken = userToken();
        long movieId = createMovie(adminToken, DURATION_MINUTES);
        long theaterId = createTheater(adminToken);
        OffsetDateTime start = futureStart(9);
        long id = createSchedule(adminToken, movieId, theaterId, start);
        String freeSlot = scheduleBody(movieId, theaterId, start.plusDays(1), "10.00", "20.00");

        mockMvc.perform(get("/api/schedules")).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedules/" + id)).andExpect(status().isOk());

        mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freeSlot))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freeSlot))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(put("/api/schedules/" + id)
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start, "10.00", "20.00")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/schedules/" + id).header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden());

        // The admin still can.
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(freeSlot))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/schedules/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    // --- cross-module deletion guards ------------------------------------------------------------

    @Test
    void deletingATheaterOrMovieThatStillHasSchedulesReturns409() throws Exception {
        String token = adminToken();
        long movieId = createMovie(token, DURATION_MINUTES);
        long theaterId = createTheater(token);
        long scheduleId = createSchedule(token, movieId, theaterId, futureStart(10));

        // The schedule's ON DELETE RESTRICT foreign keys must surface as 409, never as a 500.
        mockMvc.perform(delete("/api/theaters/" + theaterId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("Theater " + theaterId)))
                .andExpect(jsonPath("$.message").value(containsString("1 schedule(s)")));

        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("Movie " + movieId)))
                .andExpect(jsonPath("$.message").value(containsString("1 schedule(s)")));

        // Neither deletion happened.
        mockMvc.perform(get("/api/theaters/" + theaterId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/movies/" + movieId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/schedules/" + scheduleId)).andExpect(status().isOk());

        // Once the schedule is gone, both deletions succeed.
        mockMvc.perform(delete("/api/schedules/" + scheduleId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/theaters/" + theaterId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/theaters/" + theaterId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/movies/" + movieId)).andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A stable future start time; {@code slot} keeps unrelated tests on different days. */
    private static OffsetDateTime futureStart(int slot) {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(1)
                .plusDays(slot * 10L)
                .truncatedTo(ChronoUnit.HOURS)
                .withHour(9);
    }

    private static String iso(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }

    private java.time.Instant instant(JsonNode node, String field) {
        return OffsetDateTime.parse(node.path(field).asText()).toInstant();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String scheduleBody(long movieId, long theaterId, OffsetDateTime start,
                                       String basePrice, String vipPrice) {
        return """
                {"movieId":%d,"theaterId":%d,"startTime":"%s","basePrice":%s,"vipPrice":%s}"""
                .formatted(movieId, theaterId, iso(start), basePrice, vipPrice);
    }

    private long createSchedule(String token, long movieId, long theaterId, OffsetDateTime start) throws Exception {
        String body = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(movieId, theaterId, start, "10.00", "20.00")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createMovie(String token, int durationMinutes) throws Exception {
        return createMovie(token, uniqueName("Schedule Movie"), durationMinutes);
    }

    private long createMovie(String token, String title, int durationMinutes) throws Exception {
        String body = mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":%d,"genreIds":[]}"""
                                .formatted(title, durationMinutes)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createTheater(String token) throws Exception {
        return createTheater(token, uniqueName("Schedule Hall"));
    }

    private long createTheater(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","location":"Test","totalRows":2,"seatsPerRow":3,"vipRows":["A"]}"""
                                .formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long idOf(String jsonBody) throws Exception {
        return objectMapper.readTree(jsonBody).path("id").asLong();
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(adminEmail, adminPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    private String userToken() throws Exception {
        String email = "schedules-user-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Schedule Fan","email":"%s","password":"%s"}"""
                                .formatted(email, USER_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }
}

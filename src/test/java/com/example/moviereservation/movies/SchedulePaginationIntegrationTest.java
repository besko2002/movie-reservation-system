package com.example.moviereservation.movies;

import com.example.moviereservation.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused integration test verifying discoverability of schedules when more than 100 upcoming
 * schedules exist.
 *
 * <p>The backend caps single-page responses at {@code MAX_PAGE_SIZE = 100}. Clients fetching only
 * page 0 miss all schedules beyond the first 100. This test creates 105 non-overlapping upcoming
 * schedules for a theater and verifies that:
 * <ol>
 *   <li>Page 0 (size=100) returns exactly 100 schedules with {@code last=false} and {@code totalElements=105}.</li>
 *   <li>Page 1 returns the remaining 5 schedules with {@code last=true}.</li>
 *   <li>Iterative multi-page fetching (the strategy adopted by the UI's {@code loadAllSchedules})
 *       discovers all 105 schedules, including those beyond the 100 boundary.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SchedulePaginationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void moreThan100UpcomingSchedulesAreDiscoverableAcrossPages() throws Exception {
        // 1. Admin login to create movie and theater
        String adminToken = loginAdmin();
        long movieId = createMovie(adminToken, "Pagination Feature Film", 60);
        long theaterId = createTheater(adminToken, "Pagination Hall");

        // 2. Batch insert 105 non-overlapping future schedules for this theater
        int totalSchedules = 105;
        OffsetDateTime baseTime = OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(1)
                .truncatedTo(ChronoUnit.HOURS);

        List<Object[]> batchArgs = new ArrayList<>(totalSchedules);
        for (int i = 0; i < totalSchedules; i++) {
            OffsetDateTime start = baseTime.plusHours(i * 2L);
            OffsetDateTime end = start.plusMinutes(75);
            batchArgs.add(new Object[]{
                    movieId,
                    theaterId,
                    Timestamp.from(start.toInstant()),
                    Timestamp.from(end.toInstant())
            });
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO movie_schedules (movie_id, theater_id, start_time, end_time, base_price, vip_price, created_at)
                VALUES (?, ?, ?, ?, 10.00, 20.00, now())
                """, batchArgs);

        String fromIso = baseTime.minusHours(1).toString();

        // 3. Verify page 0 (size=100): clamped to 100, last=false
        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("from", fromIso)
                        .param("page", "0")
                        .param("size", "100")
                        .param("sort", "startTime,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(totalSchedules))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content", hasSize(100)));

        // 4. Verify page 1: remaining 5 items, last=true
        mockMvc.perform(get("/api/schedules")
                        .param("theaterId", String.valueOf(theaterId))
                        .param("from", fromIso)
                        .param("page", "1")
                        .param("size", "100")
                        .param("sort", "startTime,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(totalSchedules))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content", hasSize(5)));

        // 5. Simulate the UI pagination strategy: iterate until last=true
        List<Long> discoveredScheduleIds = new ArrayList<>();
        int pageIndex = 0;
        boolean lastPage = false;
        while (!lastPage) {
            String pageJson = mockMvc.perform(get("/api/schedules")
                            .param("theaterId", String.valueOf(theaterId))
                            .param("from", fromIso)
                            .param("page", String.valueOf(pageIndex))
                            .param("size", "100")
                            .param("sort", "startTime,asc"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode pageNode = objectMapper.readTree(pageJson);
            for (JsonNode item : pageNode.path("content")) {
                discoveredScheduleIds.add(item.path("id").asLong());
            }
            lastPage = pageNode.path("last").asBoolean();
            pageIndex++;
        }

        assertThat(discoveredScheduleIds).hasSize(totalSchedules);
        assertThat(pageIndex).isEqualTo(2);
    }

    private String loginAdmin() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@cinema.local","password":"Admin@12345"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    private long createMovie(String token, String title, int durationMinutes) throws Exception {
        String body = mockMvc.perform(post("/api/movies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":%d,"genreIds":[]}"""
                                .formatted(title + "-" + UUID.randomUUID(), durationMinutes)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("id").asLong();
    }

    private long createTheater(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/theaters")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","location":"Pagination Test","totalRows":2,"seatsPerRow":2,"vipRows":["A"]}"""
                                .formatted(name + "-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("id").asLong();
    }
}

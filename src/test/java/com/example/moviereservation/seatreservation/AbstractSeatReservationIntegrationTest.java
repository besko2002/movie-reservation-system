package com.example.moviereservation.seatreservation;

import com.example.moviereservation.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared full-context setup and fixtures for the phase 6 (seat reservation) integration tests
 * against the real PostgreSQL started by {@link TestcontainersConfiguration}.
 *
 * <p>Isolation inside the shared database comes from fixtures: every test creates its own theater,
 * movie, schedule and users, so holds of one test can never collide with another test's seats.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AbstractSeatReservationIntegrationTest {

    protected static final String USER_PASSWORD = "Sup3rSecret!";
    protected static final int DURATION_MINUTES = 120;
    protected static final String BASE_PRICE = "10.00";
    protected static final String VIP_PRICE = "25.00";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Value("${app.admin.email}")
    protected String adminEmail;

    @Value("${app.admin.password}")
    protected String adminPassword;

    @Value("${app.reservation.hold-minutes}")
    protected int holdMinutes;

    @Value("${app.reservation.max-seats}")
    protected int maxSeats;

    /** An authenticated test user: its database id and a valid bearer token. */
    protected record TestUser(long id, String token) {
    }

    /**
     * A ready-to-book showtime: a fresh movie in a fresh 2x3 theater whose row A is VIP, so the
     * seat list is A1,A2,A3 (VIP, {@value #VIP_PRICE}) then B1,B2,B3 (NORMAL, {@value #BASE_PRICE}).
     */
    protected record Show(long movieId, long theaterId, long scheduleId, List<Long> seatIds) {

        /** @param index zero-based index in seat map order (0..2 = VIP row A, 3..5 = normal row B) */
        long seat(int index) {
            return seatIds.get(index);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    protected Show createShow(String adminToken) throws Exception {
        return createShow(adminToken, futureStart());
    }

    protected Show createShow(String adminToken, OffsetDateTime start) throws Exception {
        long movieId = createMovie(adminToken);
        long theaterId = createTheater(adminToken, 2, 3);
        long scheduleId = createSchedule(adminToken, movieId, theaterId, start);
        return new Show(movieId, theaterId, scheduleId, seatIdsOfSeatMap(scheduleId));
    }

    protected long createMovie(String adminToken) throws Exception {
        String body = mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":%d,"genreIds":[]}"""
                                .formatted(uniqueName("Reservation Movie"), DURATION_MINUTES)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    protected long createTheater(String adminToken, int totalRows, int seatsPerRow) throws Exception {
        String body = mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","location":"Test","totalRows":%d,"seatsPerRow":%d,"vipRows":["A"]}"""
                                .formatted(uniqueName("Reservation Hall"), totalRows, seatsPerRow)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    protected long createSchedule(String adminToken, long movieId, long theaterId, OffsetDateTime start)
            throws Exception {
        String body = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"movieId":%d,"theaterId":%d,"startTime":"%s","basePrice":%s,"vipPrice":%s}"""
                                .formatted(movieId, theaterId, iso(start), BASE_PRICE, VIP_PRICE)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    // --- requests ------------------------------------------------------------------------------

    protected JsonNode seatMap(long scheduleId) throws Exception {
        String body = mockMvc.perform(get("/api/schedules/" + scheduleId + "/seats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    protected List<Long> seatIdsOfSeatMap(long scheduleId) throws Exception {
        List<Long> seatIds = new ArrayList<>();
        for (JsonNode seat : seatMap(scheduleId).path("seats")) {
            seatIds.add(seat.path("seatId").asLong());
        }
        return seatIds;
    }

    /** @return the status of one seat in the seat map, e.g. {@code AVAILABLE} */
    protected String seatStatus(long scheduleId, long seatId) throws Exception {
        for (JsonNode seat : seatMap(scheduleId).path("seats")) {
            if (seat.path("seatId").asLong() == seatId) {
                return seat.path("status").asText();
            }
        }
        throw new AssertionError("Seat " + seatId + " is not part of the seat map of schedule " + scheduleId);
    }

    protected static String holdBody(long scheduleId, List<Long> seatIds) {
        String ids = seatIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return """
                {"scheduleId":%d,"seatIds":[%s]}""".formatted(scheduleId, ids);
    }

    // --- users ---------------------------------------------------------------------------------

    protected String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(adminEmail, adminPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    protected TestUser registerUser() throws Exception {
        String email = "reservation-user-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Seat Fan","email":"%s","password":"%s"}"""
                                .formatted(email, USER_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode auth = objectMapper.readTree(body);
        return new TestUser(auth.path("user").path("id").asLong(), auth.path("accessToken").asText());
    }

    // --- helpers -------------------------------------------------------------------------------

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(2)
                .truncatedTo(ChronoUnit.HOURS)
                .withHour(9);
    }

    protected static String iso(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }

    protected static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected long idOf(String jsonBody) throws Exception {
        return objectMapper.readTree(jsonBody).path("id").asLong();
    }
}

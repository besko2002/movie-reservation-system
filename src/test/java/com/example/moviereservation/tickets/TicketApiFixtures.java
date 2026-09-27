package com.example.moviereservation.tickets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
 * Fixtures shared by the phase 7 tests: an admin token, showtimes, users and seat holds, all built
 * through the public HTTP API so a test never depends on another module's internals.
 *
 * <p>It is a plain collaborator rather than a base class, because the "payments disabled" test needs
 * the same fixtures in a <em>different</em> application context (one without the fake gateway) and
 * must not inherit that context's annotations.
 *
 * <p>Isolation inside the shared database comes from the fixtures themselves: every test creates its
 * own theater, movie, schedule and users, so seats of one test can never collide with another's.
 */
class TicketApiFixtures {

    static final String USER_PASSWORD = "Sup3rSecret!";
    static final int DURATION_MINUTES = 120;
    static final String BASE_PRICE = "10.00";
    static final String VIP_PRICE = "25.00";

    /** An authenticated test user: its database id and a valid bearer token. */
    record TestUser(long id, String token) {
    }

    /**
     * A ready-to-book showtime: a fresh movie in a fresh 2x3 theater whose row A is VIP, so the seat
     * list is A1,A2,A3 (VIP, {@value #VIP_PRICE}) then B1,B2,B3 (NORMAL, {@value #BASE_PRICE}).
     */
    record Show(long movieId, long theaterId, long scheduleId, List<Long> seatIds) {

        /** @param index zero-based index in seat map order (0..2 = VIP row A, 3..5 = normal row B) */
        long seat(int index) {
            return seatIds.get(index);
        }
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final String adminEmail;
    private final String adminPassword;

    TicketApiFixtures(MockMvc mockMvc, ObjectMapper objectMapper, String adminEmail, String adminPassword) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    // --- users -------------------------------------------------------------------------------

    String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(adminEmail, adminPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    TestUser registerUser() throws Exception {
        String email = "ticket-user-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ticket Fan","email":"%s","password":"%s"}"""
                                .formatted(email, USER_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode auth = objectMapper.readTree(body);
        return new TestUser(auth.path("user").path("id").asLong(), auth.path("accessToken").asText());
    }

    // --- showtimes ---------------------------------------------------------------------------

    Show createShow(String adminToken) throws Exception {
        return createShow(adminToken, futureStart());
    }

    Show createShow(String adminToken, OffsetDateTime start) throws Exception {
        long movieId = createMovie(adminToken);
        long theaterId = createTheater(adminToken);
        long scheduleId = createSchedule(adminToken, movieId, theaterId, start);
        return new Show(movieId, theaterId, scheduleId, seatIdsOfSeatMap(scheduleId));
    }

    private long createMovie(String adminToken) throws Exception {
        String body = mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":%d,"genreIds":[]}"""
                                .formatted(uniqueName("Ticket Movie"), DURATION_MINUTES)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createTheater(String adminToken) throws Exception {
        String body = mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","location":"Test","totalRows":2,"seatsPerRow":3,"vipRows":["A"]}"""
                                .formatted(uniqueName("Ticket Hall"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createSchedule(String adminToken, long movieId, long theaterId, OffsetDateTime start)
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

    // --- seats -------------------------------------------------------------------------------

    /** Holds seats and expects the 201 answer. */
    JsonNode holdSeats(String token, long scheduleId, List<Long> seatIds) throws Exception {
        String body = tryHoldSeats(token, scheduleId, seatIds)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Holds seats without asserting the outcome, for the "another user can hold it now" checks. */
    ResultActions tryHoldSeats(String token, long scheduleId, List<Long> seatIds) throws Exception {
        String ids = seatIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return mockMvc.perform(post("/api/seat-reservations")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"scheduleId":%d,"seatIds":[%s]}""".formatted(scheduleId, ids)));
    }

    JsonNode seatMap(long scheduleId) throws Exception {
        String body = mockMvc.perform(get("/api/schedules/" + scheduleId + "/seats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    List<Long> seatIdsOfSeatMap(long scheduleId) throws Exception {
        List<Long> seatIds = new ArrayList<>();
        for (JsonNode seat : seatMap(scheduleId).path("seats")) {
            seatIds.add(seat.path("seatId").asLong());
        }
        return seatIds;
    }

    /** @return the status of one seat in the seat map, e.g. {@code AVAILABLE} or {@code HELD} */
    String seatStatus(long scheduleId, long seatId) throws Exception {
        for (JsonNode seat : seatMap(scheduleId).path("seats")) {
            if (seat.path("seatId").asLong() == seatId) {
                return seat.path("status").asText();
            }
        }
        throw new AssertionError("Seat " + seatId + " is not part of the seat map of schedule " + scheduleId);
    }

    // --- helpers -----------------------------------------------------------------------------

    static String bearer(String token) {
        return "Bearer " + token;
    }

    static OffsetDateTime futureStart() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .plusYears(2)
                .truncatedTo(ChronoUnit.HOURS)
                .withHour(9);
    }

    static String iso(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value);
    }

    static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long idOf(String jsonBody) throws Exception {
        return objectMapper.readTree(jsonBody).path("id").asLong();
    }
}

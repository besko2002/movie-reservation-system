package com.example.moviereservation.theaters;

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the theaters module (phase 4) against a real PostgreSQL started by
 * {@link TestcontainersConfiguration}.
 *
 * <p>Every fixture uses a random theater name so tests stay independent inside the shared database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TheatersIntegrationTest {

    private static final String USER_PASSWORD = "Sup3rSecret!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    // --- creation and seat generation -------------------------------------------------------

    @Test
    void creatingATheaterGeneratesTheWholeSeatGrid() throws Exception {
        String name = uniqueName("Grand Hall");

        String body = mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name, "Downtown", 4, 6, "A", "B")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/theaters/")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.location").value("Downtown"))
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.seatsPerRow").value(6))
                .andExpect(jsonPath("$.totalSeats").value(24))
                .andExpect(jsonPath("$.vipSeats").value(12))
                .andExpect(jsonPath("$.normalSeats").value(12))
                .andReturn().getResponse().getContentAsString();
        long id = idOf(body);

        // 4 rows x 6 seats = 24 seats actually persisted.
        JsonNode seats = seatsOf(id);
        assertThat(seats).hasSize(4 * 6);

        // Rows are labelled A..D and every row holds seats 1..6.
        Set<String> rowLabels = new LinkedHashSet<>();
        for (JsonNode seat : seats) {
            rowLabels.add(seat.path("rowLabel").asText());
        }
        assertThat(rowLabels).containsExactly("A", "B", "C", "D");
        for (String row : rowLabels) {
            assertThat(seatNumbersOfRow(seats, row)).containsExactly(1, 2, 3, 4, 5, 6);
        }

        // Only the requested rows are VIP.
        for (JsonNode seat : seats) {
            String row = seat.path("rowLabel").asText();
            String expectedType = row.equals("A") || row.equals("B") ? "VIP" : "NORMAL";
            assertThat(seat.path("type").asText()).as("type of seat in row " + row).isEqualTo(expectedType);
        }
    }

    @Test
    void vipRowsAreCaseInsensitiveAndDeDuplicated() throws Exception {
        long id = createTheater(uniqueName("Case Hall"), 3, 4, adminToken(), "a", "A", "b");

        mockMvc.perform(get("/api/theaters/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(12))
                .andExpect(jsonPath("$.vipSeats").value(8))
                .andExpect(jsonPath("$.normalSeats").value(4))
                .andExpect(jsonPath("$.vipRows", hasSize(2)))
                .andExpect(jsonPath("$.vipRows[0]").value("A"))
                .andExpect(jsonPath("$.vipRows[1]").value("B"));
    }

    @Test
    void vipRowOutsideTheGridReturns400() throws Exception {
        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Small Hall"), null, 3, 5, "A", "Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("outside the theater grid")));
    }

    @Test
    void duplicateTheaterNameReturns409EvenInADifferentCase() throws Exception {
        String token = adminToken();
        String name = uniqueName("Twin Hall");
        createTheater(name, 2, 2, token);

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name, null, 2, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("already exists")));

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name.toUpperCase(), null, 2, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void validationFailuresReturn400WithFieldErrors() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody("   ", null, 2, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.name").value("name must not be blank"));

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Bad"), null, 0, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.totalRows").value("totalRows must be at least 1"));

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Bad"), null, 27, 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.totalRows").value("totalRows must be at most 26"));

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Bad"), null, 5, 51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.seatsPerRow").value("seatsPerRow must be at most 50"));

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","location":"%s","totalRows":2,"seatsPerRow":2,"vipRows":[]}"""
                                .formatted(uniqueName("Bad"), "x".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.location").value("location must be at most 200 characters"));
    }

    // --- reads -------------------------------------------------------------------------------

    @Test
    void getByIdReturnsCountsAndUnknownIdReturns404() throws Exception {
        long id = createTheater(uniqueName("Read Hall"), 5, 10, adminToken(), "C");

        mockMvc.perform(get("/api/theaters/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.totalSeats").value(50))
                .andExpect(jsonPath("$.vipSeats").value(10))
                .andExpect(jsonPath("$.normalSeats").value(40))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/theaters/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));

        mockMvc.perform(get("/api/theaters/9999999/seats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void seatsEndpointIsOrderedByRowThenSeatNumber() throws Exception {
        long id = createTheater(uniqueName("Ordered Hall"), 3, 4, adminToken(), "B");

        JsonNode seats = seatsOf(id);
        assertThat(seats).hasSize(12);

        List<String> actual = new ArrayList<>();
        for (JsonNode seat : seats) {
            actual.add(seat.path("rowLabel").asText() + seat.path("seatNumber").asInt());
        }
        assertThat(actual).containsExactly(
                "A1", "A2", "A3", "A4",
                "B1", "B2", "B3", "B4",
                "C1", "C2", "C3", "C4");
    }

    @Test
    void listIsPaginatedWithTheSharedEnvelope() throws Exception {
        String token = adminToken();
        createTheater(uniqueName("Page Hall"), 2, 2, token);
        createTheater(uniqueName("Page Hall"), 2, 2, token);
        createTheater(uniqueName("Page Hall"), 2, 2, token);

        mockMvc.perform(get("/api/theaters").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.content[0].id").exists())
                .andExpect(jsonPath("$.content[0].totalSeats").exists());

        // Page size is clamped to 100.
        mockMvc.perform(get("/api/theaters").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get("/api/theaters").param("sort", "name,desc"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/theaters").param("sort", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unknown sort property")));
    }

    // --- update and delete -------------------------------------------------------------------

    @Test
    void updateThatChangesTheGridRegeneratesTheSeats() throws Exception {
        String token = adminToken();
        String name = uniqueName("Rebuild Hall");
        long id = createTheater(name, 3, 4, token, "A");
        Set<Long> oldSeatIds = seatIdsOf(id);
        assertThat(oldSeatIds).hasSize(12);

        mockMvc.perform(put("/api/theaters/" + id)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name, "New wing", 5, 6, "B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.seatsPerRow").value(6))
                .andExpect(jsonPath("$.totalSeats").value(30))
                .andExpect(jsonPath("$.vipSeats").value(6))
                .andExpect(jsonPath("$.normalSeats").value(24))
                .andExpect(jsonPath("$.location").value("New wing"));

        Set<Long> newSeatIds = seatIdsOf(id);
        assertThat(newSeatIds).hasSize(30);
        assertThat(newSeatIds).doesNotContainAnyElementsOf(oldSeatIds);

        // The VIP row moved from A to B.
        JsonNode seats = seatsOf(id);
        for (JsonNode seat : seats) {
            String row = seat.path("rowLabel").asText();
            assertThat(seat.path("type").asText())
                    .as("type of seat in row " + row)
                    .isEqualTo(row.equals("B") ? "VIP" : "NORMAL");
        }
    }

    @Test
    void updateThatKeepsTheGridKeepsTheSameSeatIds() throws Exception {
        String token = adminToken();
        long id = createTheater(uniqueName("Stable Hall"), 3, 4, token, "A");
        Set<Long> oldSeatIds = seatIdsOf(id);
        String renamed = uniqueName("Stable Hall Renamed");

        mockMvc.perform(put("/api/theaters/" + id)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(renamed, "Same grid", 3, 4, "a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(renamed))
                .andExpect(jsonPath("$.location").value("Same grid"))
                .andExpect(jsonPath("$.totalSeats").value(12));

        assertThat(seatIdsOf(id)).isEqualTo(oldSeatIds);
    }

    @Test
    void updateOfUnknownTheaterReturns404() throws Exception {
        mockMvc.perform(put("/api/theaters/9999999")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Ghost"), null, 2, 2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void deleteRemovesTheTheaterAndItsSeats() throws Exception {
        String token = adminToken();
        long id = createTheater(uniqueName("Doomed Hall"), 2, 3, token, "A");
        assertThat(seatIdsOf(id)).hasSize(6);

        mockMvc.perform(delete("/api/theaters/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/theaters/" + id))
                .andExpect(status().isNotFound());

        // The seats went with the theater.
        mockMvc.perform(get("/api/theaters/" + id + "/seats"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/theaters/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    // --- authorization -----------------------------------------------------------------------

    @Test
    void anonymousCanReadButNotWrite() throws Exception {
        long id = createTheater(uniqueName("Public Hall"), 2, 3, adminToken(), "A");

        mockMvc.perform(get("/api/theaters")).andExpect(status().isOk());
        mockMvc.perform(get("/api/theaters/" + id)).andExpect(status().isOk());
        mockMvc.perform(get("/api/theaters/" + id + "/seats")).andExpect(status().isOk());

        mockMvc.perform(post("/api/theaters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Sneaky"), null, 2, 2)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(delete("/api/theaters/" + id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenCannotWriteTheaters() throws Exception {
        String adminToken = adminToken();
        String userToken = userToken();
        String name = uniqueName("Protected Hall");
        long id = createTheater(name, 2, 3, adminToken, "A");

        mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(uniqueName("Nope"), null, 2, 2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(put("/api/theaters/" + id)
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name, null, 2, 3, "A")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/theaters/" + id).header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden());

        // The admin still can.
        mockMvc.perform(put("/api/theaters/" + id)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name, "Admin wing", 2, 3, "A")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/theaters/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String theaterBody(String name, String location, int totalRows, int seatsPerRow,
                                      String... vipRows) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < vipRows.length; i++) {
            rows.append(i == 0 ? "" : ",").append('"').append(vipRows[i]).append('"');
        }
        String locationJson = location == null ? "null" : "\"" + location + "\"";
        return """
                {"name":"%s","location":%s,"totalRows":%d,"seatsPerRow":%d,"vipRows":[%s]}"""
                .formatted(name, locationJson, totalRows, seatsPerRow, rows);
    }

    private long createTheater(String name, int totalRows, int seatsPerRow, String token, String... vipRows)
            throws Exception {
        String body = mockMvc.perform(post("/api/theaters")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(name, null, totalRows, seatsPerRow, vipRows)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private JsonNode seatsOf(long theaterId) throws Exception {
        String body = mockMvc.perform(get("/api/theaters/" + theaterId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private Set<Long> seatIdsOf(long theaterId) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode seat : seatsOf(theaterId)) {
            ids.add(seat.path("id").asLong());
        }
        return ids;
    }

    private static List<Integer> seatNumbersOfRow(JsonNode seats, String rowLabel) {
        List<Integer> numbers = new ArrayList<>();
        for (JsonNode seat : seats) {
            if (seat.path("rowLabel").asText().equals(rowLabel)) {
                numbers.add(seat.path("seatNumber").asInt());
            }
        }
        return numbers;
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
        String email = "theaters-user-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Theater Fan","email":"%s","password":"%s"}"""
                                .formatted(email, USER_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }
}

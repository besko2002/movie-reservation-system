package com.example.moviereservation.tickets;

import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/tickets/me} and {@code GET /api/tickets/{id}}: reading tickets. */
class TicketReadIntegrationTest extends AbstractTicketIntegrationTest {

    @Test
    @DisplayName("/me lists only the caller's tickets, newest first, with their seats")
    void meListsOnlyOwnTicketsNewestFirst() throws Exception {
        String adminToken = fixtures.adminToken();
        Show firstShow = fixtures.createShow(adminToken);
        Show secondShow = fixtures.createShow(adminToken);
        TestUser user = fixtures.registerUser();
        TestUser other = fixtures.registerUser();

        long olderTicket = pendingTicket(user.token(), firstShow.scheduleId(), List.of(firstShow.seat(0)));
        long newerTicket = pendingTicket(user.token(), secondShow.scheduleId(),
                List.of(secondShow.seat(3), secondShow.seat(4)));
        long foreignTicket = pendingTicket(other.token(), firstShow.scheduleId(), List.of(firstShow.seat(1)));

        JsonNode mine = objectMapper.readTree(mockMvc.perform(get("/api/tickets/me")
                        .header("Authorization", TicketApiFixtures.bearer(user.token())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(mine.isArray()).isTrue();
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).path("id").asLong()).isEqualTo(newerTicket);
        assertThat(mine.get(1).path("id").asLong()).isEqualTo(olderTicket);
        assertThat(mine.get(0).path("id").asLong()).isNotEqualTo(foreignTicket);
        assertThat(mine.get(1).path("id").asLong()).isNotEqualTo(foreignTicket);

        // Seats travel with the ticket, with their labels and prices.
        JsonNode newest = mine.get(0);
        assertThat(newest.path("seatCount").asInt()).isEqualTo(2);
        assertThat(newest.path("seats")).hasSize(2);
        assertThat(newest.path("seats").get(0).path("rowLabel").asText()).isEqualTo("B");
        assertThat(newest.path("seats").get(0).path("status").asText()).isEqualTo("HELD");
        assertThat(new java.math.BigDecimal(newest.path("seats").get(0).path("price").asText()))
                .isEqualByComparingTo("10.00");
        assertThat(newest.path("movieTitle").asText()).isNotBlank();
    }

    @Test
    @DisplayName("/me is an empty array for a user who never bought anything")
    void meIsEmptyForNewUser() throws Exception {
        TestUser user = fixtures.registerUser();

        mockMvc.perform(get("/api/tickets/me")
                        .header("Authorization", TicketApiFixtures.bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("/{id}: the owner reads it, a stranger gets 403, an ADMIN reads it")
    void ticketByIdRespectsOwnership() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);
        TestUser owner = fixtures.registerUser();
        TestUser stranger = fixtures.registerUser();
        long ticketId = pendingTicket(owner.token(), show.scheduleId(), List.of(show.seat(0)));

        mockMvc.perform(get("/api/tickets/" + ticketId)
                        .header("Authorization", TicketApiFixtures.bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.userId").value(owner.id()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.seats.length()").value(1));

        mockMvc.perform(get("/api/tickets/" + ticketId)
                        .header("Authorization", TicketApiFixtures.bearer(stranger.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/tickets/" + ticketId)
                        .header("Authorization", TicketApiFixtures.bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId));
    }

    @Test
    @DisplayName("/{id} of an unknown ticket is a 404, and without a token a 401")
    void unknownTicketAndAnonymousAccess() throws Exception {
        TestUser user = fixtures.registerUser();

        mockMvc.perform(get("/api/tickets/9999999")
                        .header("Authorization", TicketApiFixtures.bearer(user.token())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/tickets/me"))
                .andExpect(status().isUnauthorized());
    }
}

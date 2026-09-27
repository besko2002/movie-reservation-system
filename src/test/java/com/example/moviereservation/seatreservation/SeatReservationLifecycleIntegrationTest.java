package com.example.moviereservation.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Release, {@code GET /api/seat-reservations/me}, hold expiry, and the two cross-module seams that
 * phase 6 closes ({@code ScheduleUsagePolicy} and {@code SeatUsagePolicy}).
 */
class SeatReservationLifecycleIntegrationTest extends AbstractSeatReservationIntegrationTest {

    @Autowired
    private SeatReservationExpirySweeper sweeper;

    // --- release -------------------------------------------------------------------------------

    @Test
    void ownerCanReleaseAHoldAndTheSeatBecomesAvailableAgain() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();
        long reservationId = hold(user, show, show.seat(0));

        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");

        mockMvc.perform(delete("/api/seat-reservations/" + reservationId)
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isNoContent());

        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("AVAILABLE");
        assertThat(statusInDatabase(reservationId)).isEqualTo("RELEASED");

        mockMvc.perform(get("/api/seat-reservations/me").header("Authorization", bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // Releasing it twice is a 409, not a second 204.
        mockMvc.perform(delete("/api/seat-reservations/" + reservationId)
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already released")));
    }

    @Test
    void anotherUsersReservationIsRejectedWith403AndUnknownIdWith404() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser owner = registerUser();
        TestUser stranger = registerUser();
        long reservationId = hold(owner, show, show.seat(0));

        // Documented decision: someone else's reservation is 403 (Forbidden), not a 404 disguise.
        mockMvc.perform(delete("/api/seat-reservations/" + reservationId)
                        .header("Authorization", bearer(stranger.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // The hold is untouched.
        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");
        assertThat(statusInDatabase(reservationId)).isEqualTo("HELD");

        mockMvc.perform(delete("/api/seat-reservations/9999999")
                        .header("Authorization", bearer(stranger.token())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(delete("/api/seat-reservations/" + reservationId))
                .andExpect(status().isUnauthorized());

        // An ADMIN may release any hold.
        mockMvc.perform(delete("/api/seat-reservations/" + reservationId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void meListsOnlyTheCallersActiveHolds() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser first = registerUser();
        TestUser second = registerUser();

        long firstReservation = hold(first, show, show.seat(0));
        hold(first, show, show.seat(1));
        long secondReservation = hold(second, show, show.seat(3));

        String body = mockMvc.perform(get("/api/seat-reservations/me")
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode reservation : objectMapper.readTree(body)) {
            assertThat(reservation.path("userId").asLong()).isEqualTo(first.id());
            assertThat(reservation.path("status").asText()).isEqualTo("HELD");
            assertThat(reservation.path("expiresAt").asText()).isNotBlank();
            assertThat(reservation.path("ticketId").isNull()).isTrue();
        }

        mockMvc.perform(get("/api/seat-reservations/me").header("Authorization", bearer(second.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(secondReservation));

        mockMvc.perform(get("/api/seat-reservations/me"))
                .andExpect(status().isUnauthorized());

        // Releasing one of the two removes exactly that one.
        mockMvc.perform(delete("/api/seat-reservations/" + firstReservation)
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/seat-reservations/me").header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // --- expiry --------------------------------------------------------------------------------

    @Test
    void anExpiredHoldFreesTheSeatLazilyAndTheSweeperReleasesIt() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();
        long reservationId = hold(user, show, show.seat(0));

        // Push the hold into the past, exactly like the passage of time would.
        jdbcTemplate.update("update seat_reservations set expires_at = ? where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), reservationId);

        // Lazily correct: the row is still HELD in the table, but the seat map already says free.
        assertThat(statusInDatabase(reservationId)).isEqualTo("HELD");
        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("AVAILABLE");
        mockMvc.perform(get("/api/seat-reservations/me").header("Authorization", bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // The scheduled job flips it to RELEASED.
        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(statusInDatabase(reservationId)).isEqualTo("RELEASED");

        // And the very same seat can be held again: the partial unique index tolerates the RELEASED
        // row, which is the whole point of making it partial.
        TestUser other = registerUser();
        long newReservationId = hold(other, show, show.seat(0));
        assertThat(newReservationId).isNotEqualTo(reservationId);
        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");
    }

    @Test
    void anExpiredHoldDoesNotBlockTheSameUserEvenBeforeTheSweeperRuns() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();
        long reservationId = hold(user, show, show.seat(2));

        jdbcTemplate.update("update seat_reservations set expires_at = ? where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30), reservationId);

        // No sweep in between: holdSeats releases the expired row itself before inserting.
        long newReservationId = hold(user, show, show.seat(2));
        assertThat(newReservationId).isNotEqualTo(reservationId);
        assertThat(statusInDatabase(reservationId)).isEqualTo("RELEASED");
        assertThat(statusInDatabase(newReservationId)).isEqualTo("HELD");
    }

    // --- cross-module seams --------------------------------------------------------------------

    @Test
    void activeHoldsBlockScheduleDeletionAndSeatRegeneration() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();
        long reservationId = hold(user, show, show.seat(0));

        // ScheduleUsagePolicy (implemented in this module) refuses the deletion.
        mockMvc.perform(delete("/api/schedules/" + show.scheduleId()).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("Schedule " + show.scheduleId())))
                .andExpect(jsonPath("$.message").value(containsString("1 active seat reservation(s)")));

        // SeatUsagePolicy refuses to throw the seat grid away.
        mockMvc.perform(put("/api/theaters/" + show.theaterId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(3, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("Theater " + show.theaterId())))
                .andExpect(jsonPath("$.message").value(containsString("1 active seat reservation(s)")));

        // Nothing changed.
        mockMvc.perform(get("/api/schedules/" + show.scheduleId())).andExpect(status().isOk());
        assertThat(seatMap(show.scheduleId()).path("seats")).hasSize(6);

        // After releasing the hold, both operations are allowed again.
        mockMvc.perform(delete("/api/seat-reservations/" + reservationId)
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isNoContent());

        // The released row referenced the schedule with ON DELETE RESTRICT; the seam discards its
        // own never-paid history, so the deletion is a clean 204 instead of a database error.
        mockMvc.perform(delete("/api/schedules/" + show.scheduleId()).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/schedules/" + show.scheduleId())).andExpect(status().isNotFound());

        mockMvc.perform(put("/api/theaters/" + show.theaterId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(3, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(9));
    }

    @Test
    void aReleasedHoldDoesNotBlockSeatRegeneration() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();
        long reservationId = hold(user, show, show.seat(0));

        mockMvc.perform(delete("/api/seat-reservations/" + reservationId)
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/theaters/" + show.theaterId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(theaterBody(3, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(9));

        // The never-paid released row went with the seat it pointed at.
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from seat_reservations where id = ?", Integer.class, reservationId);
        assertThat(rows).isZero();
        assertThat(seatMap(show.scheduleId()).path("seats")).hasSize(9);
    }

    // --- helpers -------------------------------------------------------------------------------

    private long hold(TestUser user, Show show, long seatId) throws Exception {
        String body = mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(seatId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("reservationIds").get(0).asLong();
    }

    private String statusInDatabase(long reservationId) {
        return jdbcTemplate.queryForObject(
                "select status from seat_reservations where id = ?", String.class, reservationId);
    }

    private static String theaterBody(int totalRows, int seatsPerRow) {
        return """
                {"name":"%s","location":"Test","totalRows":%d,"seatsPerRow":%d,"vipRows":["A"]}"""
                .formatted(uniqueName("Reservation Hall"), totalRows, seatsPerRow);
    }
}

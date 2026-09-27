package com.example.moviereservation.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seat map and seat holds: happy paths, validation and conflicts. */
class SeatMapAndHoldIntegrationTest extends AbstractSeatReservationIntegrationTest {

    // --- seat map ------------------------------------------------------------------------------

    @Test
    void seatMapIsPublicAndStartsAllAvailableWithPerTypePrices() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);

        // No Authorization header at all: the seat map is public like every other schedule read.
        JsonNode map = seatMap(show.scheduleId());
        assertThat(map.path("scheduleId").asLong()).isEqualTo(show.scheduleId());
        assertThat(map.path("theaterId").asLong()).isEqualTo(show.theaterId());
        assertThat(map.path("seats")).hasSize(6);

        for (JsonNode seat : map.path("seats")) {
            assertThat(seat.path("status").asText()).isEqualTo("AVAILABLE");
            String expectedPrice = "A".equals(seat.path("rowLabel").asText()) ? VIP_PRICE : BASE_PRICE;
            assertThat(new BigDecimal(seat.path("price").asText())).isEqualByComparingTo(expectedPrice);
            assertThat(seat.path("type").asText())
                    .isEqualTo("A".equals(seat.path("rowLabel").asText()) ? "VIP" : "NORMAL");
        }
    }

    @Test
    void seatMapReportsHeldSeatsAndUnknownScheduleReturns404() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();

        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isCreated());

        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");
        assertThat(seatStatus(show.scheduleId(), show.seat(1))).isEqualTo("AVAILABLE");

        mockMvc.perform(get("/api/schedules/9999999/seats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // --- holding -------------------------------------------------------------------------------

    @Test
    void holdingAVipAndANormalSeatReturns201WithTotalPriceAndExpiry() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        String body = mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0), show.seat(3)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").value(show.scheduleId()))
                .andExpect(jsonPath("$.reservationIds", hasSize(2)))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andReturn().getResponse().getContentAsString();

        JsonNode hold = objectMapper.readTree(body);
        // A1 is VIP (25.00), B1 is NORMAL (10.00).
        assertThat(new BigDecimal(hold.path("totalPrice").asText())).isEqualByComparingTo("35.00");
        assertThat(hold.path("seats").get(0).path("type").asText()).isEqualTo("VIP");
        assertThat(hold.path("seats").get(1).path("type").asText()).isEqualTo("NORMAL");

        OffsetDateTime expiresAt = OffsetDateTime.parse(hold.path("expiresAt").asText());
        assertThat(expiresAt).isAfter(before.plusMinutes(holdMinutes).minusMinutes(1));
        assertThat(expiresAt).isBefore(before.plusMinutes(holdMinutes).plusMinutes(2));

        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");
        assertThat(seatStatus(show.scheduleId(), show.seat(3))).isEqualTo("HELD");
    }

    @Test
    void anonymousHoldIsRejectedWith401() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);

        mockMvc.perform(post("/api/seat-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void invalidHoldRequestsReturn400() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        Show otherShow = createShow(admin);
        TestUser user = registerUser();

        // A seat of a different theater.
        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(otherShow.seat(0)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("does not belong to the theater")));

        // Duplicate seat ids.
        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0), show.seat(0)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("duplicates")));

        // Empty seat list (bean validation).
        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.seatIds").value("seatIds must not be empty"));

        // More seats than app.reservation.max-seats.
        List<Long> tooMany = new ArrayList<>();
        for (int index = 0; index <= maxSeats; index++) {
            tooMany.add(1_000_000L + index);
        }
        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), tooMany)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(containsString("At most %d seat(s)".formatted(maxSeats))));

        // Nothing of the above was held.
        for (long seatId : show.seatIds()) {
            assertThat(seatStatus(show.scheduleId(), seatId)).isEqualTo("AVAILABLE");
        }
    }

    @Test
    void holdingSeatsOfAStartedScheduleReturns400AndUnknownScheduleReturns404() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser user = registerUser();

        // A schedule cannot be created in the past, so an existing one is moved there directly.
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusHours(3);
        jdbcTemplate.update("update movie_schedules set start_time = ?, end_time = ? where id = ?",
                start, start.plusHours(2), show.scheduleId());

        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already started")));

        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(9999999L, List.of(show.seat(0)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // --- conflicts -----------------------------------------------------------------------------

    @Test
    void secondUserHoldingTheSameSeatGets409NamingTheSeat() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser first = registerUser();
        TestUser second = registerUser();

        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(first.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(second.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                // "A1 (id ...)": the seat is named, not just its id.
                .andExpect(jsonPath("$.message").value(containsString("A1 (id " + show.seat(0) + ")")));
    }

    @Test
    void aPartiallyOverlappingRequestHoldsNothing() throws Exception {
        String admin = adminToken();
        Show show = createShow(admin);
        TestUser first = registerUser();
        TestUser second = registerUser();

        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(first.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(0)))))
                .andExpect(status().isCreated());

        // A1 is taken, A2/B1 are free: the whole request must fail.
        mockMvc.perform(post("/api/seat-reservations")
                        .header("Authorization", bearer(second.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(show.scheduleId(), List.of(show.seat(1), show.seat(0), show.seat(3)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("A1 (id " + show.seat(0) + ")")));

        // All-or-nothing: the free seats of that request stayed available.
        assertThat(seatStatus(show.scheduleId(), show.seat(1))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(show.scheduleId(), show.seat(3))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");

        // The second user owns no reservation at all.
        mockMvc.perform(get("/api/seat-reservations/me").header("Authorization", bearer(second.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}

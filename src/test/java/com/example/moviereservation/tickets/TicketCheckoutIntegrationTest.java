package com.example.moviereservation.tickets;

import com.example.moviereservation.seatreservation.SeatReservationExpirySweeper;
import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/tickets}: turning live seat holds into a payable ticket. */
class TicketCheckoutIntegrationTest extends AbstractTicketIntegrationTest {

    @Autowired
    private SeatReservationExpirySweeper sweeper;

    @Value("${app.ticket.checkout-minutes}")
    private int checkoutMinutes;

    @Value("${app.reservation.hold-minutes}")
    private int holdMinutes;

    @Test
    @DisplayName("checkout of a VIP + NORMAL hold creates a PENDING_PAYMENT ticket with the summed price")
    void checkoutCreatesPendingTicket() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        // A1 is VIP (25.00), B1 is NORMAL (10.00) -> 35.00.
        List<Long> seats = List.of(show.seat(0), show.seat(3));
        fixtures.holdSeats(user.token(), show.scheduleId(), seats);

        JsonNode body = checkout(user.token(), show.scheduleId());
        long ticketId = body.path("ticketId").asLong();

        assertThat(body.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(new BigDecimal(body.path("totalPrice").asText())).isEqualByComparingTo("35.00");
        assertThat(body.path("currency").asText()).isEqualTo("usd");
        assertThat(body.path("checkoutUrl").asText()).startsWith(FakePaymentGateway.CHECKOUT_URL_PREFIX);
        assertThat(body.path("expiresAt").isMissingNode()).isFalse();

        // The ticket waits for money and the seats are only reserved, never yet sold.
        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
        assertThat(ticketSessionId(ticketId)).isEqualTo(FakePaymentGateway.sessionIdOf(ticketId));
        assertThat(reservationStatusesOfUser(user.id(), show.scheduleId())).containsExactly("HELD", "HELD");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");

        // One line item per seat, with the per-seat prices - what the buyer will see.
        CheckoutSessionRequest request = paymentGateway.checkoutRequestFor(ticketId).orElseThrow();
        assertThat(request.lineItems()).extracting(CheckoutLineItem::amount)
                .containsExactly(new BigDecimal("25.00"), new BigDecimal("10.00"));
        assertThat(request.userId()).isEqualTo(user.id());
        assertThat(request.scheduleId()).isEqualTo(show.scheduleId());
    }

    @Test
    @DisplayName("checkout without a live hold is a 409")
    void checkoutWithoutHoldsIsConflict() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();

        postCheckout(user.token(), show.scheduleId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no live seat hold")));

        assertThat(ticketCount(user.id(), show.scheduleId())).isZero();
    }

    @Test
    @DisplayName("checkout without a token is a 401")
    void checkoutAnonymousIsUnauthorized() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());

        mockMvc.perform(post("/api/tickets")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"scheduleId":%d}""".formatted(show.scheduleId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("checkout of an unknown schedule is a 404")
    void checkoutUnknownScheduleIsNotFound() throws Exception {
        TestUser user = fixtures.registerUser();

        postCheckout(user.token(), 9_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("a second checkout for the same schedule returns the same ticket and session with 200")
    void secondCheckoutReturnsTheSameTicket() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        fixtures.holdSeats(user.token(), show.scheduleId(), List.of(show.seat(1)));

        JsonNode first = checkout(user.token(), show.scheduleId());
        String secondBody = postCheckout(user.token(), show.scheduleId())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode second = objectMapper.readTree(secondBody);

        assertThat(second.path("ticketId").asLong()).isEqualTo(first.path("ticketId").asLong());
        assertThat(second.path("checkoutUrl").asText()).isEqualTo(first.path("checkoutUrl").asText());
        assertThat(second.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        // Exactly one ticket and exactly one payment page were ever created.
        assertThat(ticketCount(user.id(), show.scheduleId())).isEqualTo(1);
        assertThat(paymentGateway.checkoutSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("checkout extends the buyer's holds to the session expiry and leaves other buyers alone")
    void checkoutAlignsTheHoldsWithTheSessionExpiry() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        TestUser other = fixtures.registerUser();
        fixtures.holdSeats(buyer.token(), show.scheduleId(), List.of(show.seat(0), show.seat(3)));
        fixtures.holdSeats(other.token(), show.scheduleId(), List.of(show.seat(4)));
        List<Instant> otherExpiriesBefore = holdExpiriesOfUser(other.id(), show.scheduleId());
        Instant expectedExpiry = Instant.now().plus(checkoutMinutes, ChronoUnit.MINUTES);

        JsonNode body = checkout(buyer.token(), show.scheduleId());
        long ticketId = body.path("ticketId").asLong();

        // The payment page is opened with an explicit expiry: without one Stripe would keep it
        // payable for 24 hours, long after the seats behind it are gone.
        CheckoutSessionRequest recorded = paymentGateway.checkoutRequestFor(ticketId).orElseThrow();
        assertThat(recorded.expiresAt()).isNotNull();
        Instant sessionExpiry = recorded.expiresAt().toInstant();
        assertThat(sessionExpiry).isCloseTo(expectedExpiry, within(10, ChronoUnit.SECONDS));
        assertThat(OffsetDateTime.parse(body.path("expiresAt").asText()).toInstant()).isEqualTo(sessionExpiry);

        // ... and the buyer's holds now die at exactly that instant instead of after the old
        // 10 minutes.
        List<Instant> buyerExpiries = holdExpiriesOfUser(buyer.id(), show.scheduleId());
        assertThat(buyerExpiries).hasSize(2).allSatisfy(expiry -> assertThat(expiry).isEqualTo(sessionExpiry));
        assertThat(sessionExpiry).isAfter(Instant.now().plus(holdMinutes, ChronoUnit.MINUTES));

        // Somebody else's holds in the same schedule are none of this checkout's business.
        assertThat(holdExpiriesOfUser(other.id(), show.scheduleId())).isEqualTo(otherExpiriesBefore);
    }

    @Test
    @DisplayName("the expiry sweeper leaves the extended holds alone once the plain hold window has passed")
    void sweeperDoesNotReleaseTheExtendedHolds() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        TestUser other = fixtures.registerUser();
        fixtures.holdSeats(buyer.token(), show.scheduleId(), List.of(show.seat(0)));
        // A hold that is never checked out, as the control: it keeps the plain hold window.
        fixtures.holdSeats(other.token(), show.scheduleId(), List.of(show.seat(1)));
        long ticketId = checkout(buyer.token(), show.scheduleId()).path("ticketId").asLong();

        // Move every hold of this schedule one minute past the plain hold window - the same thing
        // waiting app.reservation.hold-minutes + 1 would do, without waiting.
        jdbcTemplate.update(
                "UPDATE seat_reservations SET expires_at = expires_at - (? * interval '1 minute') "
                        + "WHERE schedule_id = ? AND status = 'HELD'",
                holdMinutes + 1, show.scheduleId());

        sweeper.sweep();

        // The control hold is gone; the hold that is being paid for survives, because checkout
        // pushed its expiry out to the payment window.
        assertThat(reservationStatusesOfUser(other.id(), show.scheduleId())).containsExactly("RELEASED");
        assertThat(reservationStatusesOfUser(buyer.id(), show.scheduleId())).containsExactly("HELD");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");
        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("checkout without a scheduleId is a 400 with a field error")
    void checkoutWithoutScheduleIdIsBadRequest() throws Exception {
        TestUser user = fixtures.registerUser();

        mockMvc.perform(post("/api/tickets")
                        .header("Authorization", TicketApiFixtures.bearer(user.token()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.scheduleId").exists());
    }
}

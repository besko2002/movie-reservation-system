package com.example.moviereservation.tickets;

import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A ticket pays for exactly the seats it was priced from at checkout: those rows get the ticket id
 * stamped on them, and only they can be confirmed, released or refunded with the ticket.
 */
class TicketSeatBindingIntegrationTest extends AbstractTicketIntegrationTest {

    @Test
    @DisplayName("checkout stamps the ticket id on exactly the priced holds")
    void checkoutAttachesExactlyThePricedHolds() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(3), show.seat(4)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM seat_reservations WHERE ticket_id = ? AND status = 'HELD'",
                Integer.class, ticketId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a seat held after checkout is not paid for by that checkout (exploit A)")
    void seatHeldAfterCheckoutDoesNotRideAlong() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        long pricedSeat = show.seat(3);
        long extraSeat = show.seat(4);
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(pricedSeat));

        fixtures.holdSeats(buyer.token(), show.scheduleId(), List.of(extraSeat));
        postCheckout(buyer.token(), show.scheduleId()).andExpect(status().isConflict());

        payTicket(ticketId);

        assertThat(ticketStatus(ticketId)).isEqualTo("PAID");
        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM seat_reservations WHERE seat_id = ? AND schedule_id = ? AND ticket_id IS NULL",
                String.class, extraSeat, show.scheduleId())).isEqualTo("HELD");
        assertThat(fixtures.seatStatus(show.scheduleId(), pricedSeat)).isEqualTo("BOOKED");
        assertThat(fixtures.seatStatus(show.scheduleId(), extraSeat)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("a hold attached to a pending ticket cannot be released on its own (exploit B)")
    void attachedHoldCannotBeReleasedAlone() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        JsonNode hold = fixtures.holdSeats(buyer.token(), show.scheduleId(), List.of(show.seat(3), show.seat(4)));
        long firstReservation = hold.path("reservationIds").get(0).asLong();
        long ticketId = checkout(buyer.token(), show.scheduleId()).path("ticketId").asLong();

        mockMvc.perform(delete("/api/seat-reservations/{id}", firstReservation)
                        .header("Authorization", TicketApiFixtures.bearer(buyer.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ticket")));

        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("HELD", "HELD");
    }

    @Test
    @DisplayName("payment for a ticket that lost one of its seats is refunded in full, nothing confirmed")
    void partiallyLostSeatsAreRefundedNotConfirmed() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(3), show.seat(4)));

        jdbcTemplate.update(
                "UPDATE seat_reservations SET status = 'RELEASED', expires_at = NULL "
                        + "WHERE id = (SELECT min(id) FROM seat_reservations WHERE ticket_id = ?)",
                ticketId);

        String paymentIntentId = payTicket(ticketId);

        assertThat(ticketStatus(ticketId)).isEqualTo("REFUNDED");
        assertThat(paymentGateway.refundOf(paymentIntentId).orElseThrow().amount())
                .isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(reservationStatusesOfTicket(ticketId)).containsOnly("RELEASED");
    }

    @Test
    @DisplayName("cancelling a pending ticket releases only its own holds")
    void cancellingPendingTicketReleasesOnlyItsHolds() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(3)));
        fixtures.holdSeats(buyer.token(), show.scheduleId(), List.of(show.seat(4)));

        mockMvc.perform(delete("/api/tickets/{id}", ticketId)
                        .header("Authorization", TicketApiFixtures.bearer(buyer.token())))
                .andExpect(status().isNoContent());

        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("RELEASED");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(4))).isEqualTo("HELD");
    }
}

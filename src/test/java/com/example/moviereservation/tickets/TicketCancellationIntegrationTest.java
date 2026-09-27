package com.example.moviereservation.tickets;

import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code DELETE /api/tickets/{id}}: cancellation of a pending ticket and refund of a paid one. */
class TicketCancellationIntegrationTest extends AbstractTicketIntegrationTest {

    @Test
    @DisplayName("cancelling a PENDING_PAYMENT ticket releases the seats and answers 204")
    void cancelPendingTicket() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long seatId = show.seat(0);
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(seatId));

        cancel(user.token(), ticketId).andExpect(status().isNoContent());

        assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
        assertThat(reservationStatusesOfUser(user.id(), show.scheduleId())).containsExactly("RELEASED");
        assertThat(fixtures.seatStatus(show.scheduleId(), seatId)).isEqualTo("AVAILABLE");
        // Nothing was paid, so nothing may be refunded.
        assertThat(paymentGateway.refunds()).isEmpty();
    }

    @Test
    @DisplayName("cancelling a PAID ticket well before the cutoff refunds the full amount and frees the seats")
    void cancelPaidTicketBeforeCutoffRefunds() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        // A1 (VIP 25.00) + B1 (NORMAL 10.00) = 35.00.
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(0), show.seat(3)));
        String paymentIntentId = payTicket(ticketId);
        assertThat(ticketStatus(ticketId)).isEqualTo("PAID");

        cancel(user.token(), ticketId).andExpect(status().isNoContent());

        assertThat(ticketStatus(ticketId)).isEqualTo("REFUNDED");
        assertThat(paymentGateway.refundOf(paymentIntentId)).isPresent()
                .get()
                .extracting(FakePaymentGateway.RecordedRefund::amount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("35.00"));
        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("RELEASED", "RELEASED");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("cancelling a PAID ticket inside the cutoff window is a 409 naming the cutoff")
    void cancelPaidTicketInsideCutoffIsConflict() throws Exception {
        // The showtime starts in 30 minutes, the cutoff is 2 hours before it: already passed.
        OffsetDateTime soon = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        Show show = fixtures.createShow(fixtures.adminToken(), soon);
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(0)));
        payTicket(ticketId);

        cancel(user.token(), ticketId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "cutoff of %d hour(s)".formatted(cancellationCutoffHours))));

        // Nothing changed: still paid, still holding its seats, no refund asked for.
        assertThat(ticketStatus(ticketId)).isEqualTo("PAID");
        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("CONFIRMED");
        assertThat(paymentGateway.refunds()).isEmpty();
    }

    @Test
    @DisplayName("another user cannot cancel someone else's ticket (403)")
    void cancelForeignTicketIsForbidden() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser owner = fixtures.registerUser();
        TestUser stranger = fixtures.registerUser();
        long ticketId = pendingTicket(owner.token(), show.scheduleId(), List.of(show.seat(2)));

        cancel(stranger.token(), ticketId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("cancelling an already refunded ticket is a 409")
    void cancelAlreadyRefundedTicketIsConflict() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(1)));
        payTicket(ticketId);
        cancel(user.token(), ticketId).andExpect(status().isNoContent());
        assertThat(ticketStatus(ticketId)).isEqualTo("REFUNDED");

        cancel(user.token(), ticketId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already REFUNDED")));

        // Exactly one refund, however often the client tries.
        assertThat(paymentGateway.refunds()).hasSize(1);
    }

    @Test
    @DisplayName("an ADMIN may cancel another user's ticket")
    void adminMayCancelAnyTicket() throws Exception {
        String adminToken = fixtures.adminToken();
        Show show = fixtures.createShow(adminToken);
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(4)));

        cancel(adminToken, ticketId).andExpect(status().isNoContent());

        assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancelling an unknown ticket is a 404")
    void cancelUnknownTicketIsNotFound() throws Exception {
        TestUser user = fixtures.registerUser();

        cancel(user.token(), 9_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private org.springframework.test.web.servlet.ResultActions cancel(String token, long ticketId) throws Exception {
        return mockMvc.perform(delete("/api/tickets/" + ticketId)
                .header("Authorization", TicketApiFixtures.bearer(token)));
    }
}

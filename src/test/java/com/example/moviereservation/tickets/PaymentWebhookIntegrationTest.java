package com.example.moviereservation.tickets;

import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/payments/stripe/webhook}: the only way a payment result enters the system.
 *
 * <p>Every delivery here is signed with real HMAC-SHA256 over the exact request body
 * ({@link StripeSignatures}) and verified by the production {@link StripeWebhookVerifier} — the fake
 * gateway plays no part in verification.
 */
class PaymentWebhookIntegrationTest extends AbstractTicketIntegrationTest {

    @Test
    @DisplayName("a signed checkout.session.completed pays the ticket and confirms its seats")
    void completedEventPaysTicketAndConfirmsSeats() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(),
                List.of(show.seat(0), show.seat(3)));
        String payload = StripeEvents.checkoutSessionCompleted(
                "evt_test_" + UUID.randomUUID(), FakePaymentGateway.sessionIdOf(ticketId),
                ticketId, "pi_test_completed_1");

        deliver(payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("is PAID")));

        assertThat(ticketStatus(ticketId)).isEqualTo("PAID");
        assertThat(ticketIsPaidAtSet(ticketId)).isTrue();
        assertThat(ticketPaymentIntent(ticketId)).isEqualTo("pi_test_completed_1");
        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("CONFIRMED", "CONFIRMED");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("redelivering the same event changes nothing the second time")
    void redeliveryIsIdempotent() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(1)));
        String payload = StripeEvents.checkoutSessionCompleted(
                "evt_test_redelivery", FakePaymentGateway.sessionIdOf(ticketId), ticketId, "pi_test_redelivery");

        deliver(payload).andExpect(status().isOk());
        String paidAtAfterFirst = paidAtText(ticketId);

        // Stripe delivers at least once: the very same bytes arrive again.
        deliver(payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("already PAID")));

        assertThat(ticketStatus(ticketId)).isEqualTo("PAID");
        assertThat(paidAtText(ticketId)).isEqualTo(paidAtAfterFirst);
        assertThat(ticketPaymentIntent(ticketId)).isEqualTo("pi_test_redelivery");
        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("CONFIRMED");
    }

    @Test
    @DisplayName("a tampered body is a 400 and leaves the ticket PENDING_PAYMENT")
    void tamperedPayloadIsRejected() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(2)));
        String signed = StripeEvents.checkoutSessionCompleted(
                "evt_test_tampered", FakePaymentGateway.sessionIdOf(ticketId), ticketId, "pi_test_tampered");
        // The attacker keeps the genuine signature but edits the body it was computed over.
        String tampered = signed.replace("\"amount_total\":3500", "\"amount_total\":1");

        deliver(tampered, StripeSignatures.tamperedFor(signed, WEBHOOK_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("signature")));

        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
        assertThat(ticketIsPaidAtSet(ticketId)).isFalse();
        assertThat(reservationStatusesOfUser(user.id(), show.scheduleId())).containsExactly("HELD");
    }

    @Test
    @DisplayName("a missing Stripe-Signature header is a 400 with no state change")
    void missingSignatureHeaderIsRejected() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(0)));
        String payload = StripeEvents.checkoutSessionCompleted(
                "evt_test_nosig", FakePaymentGateway.sessionIdOf(ticketId), ticketId, "pi_test_nosig");

        deliver(payload, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Stripe-Signature")));

        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("a syntactically valid but wrong signature is a 400")
    void wrongSignatureIsRejected() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(0)));
        String payload = StripeEvents.checkoutSessionCompleted(
                "evt_test_wrongsig", FakePaymentGateway.sessionIdOf(ticketId), ticketId, "pi_test_wrongsig");

        deliver(payload, StripeSignatures.wrongSignature())
                .andExpect(status().isBadRequest());

        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("an unhandled event type is accepted and ignored")
    void unknownEventTypeIsIgnored() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(4)));

        deliver(StripeEvents.unknownType("evt_test_unknown"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("ignored")));

        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
        assertThat(reservationStatusesOfUser(user.id(), show.scheduleId())).containsExactly("HELD");
    }

    @Test
    @DisplayName("checkout.session.expired expires the ticket, frees the seat and lets someone else take it")
    void expiredEventReleasesTheSeats() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        long seatId = show.seat(3);
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(seatId));

        deliver(StripeEvents.checkoutSessionExpired(
                        "evt_test_expired", FakePaymentGateway.sessionIdOf(ticketId), ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("EXPIRED")));

        assertThat(ticketStatus(ticketId)).isEqualTo("EXPIRED");
        assertThat(reservationStatusesOfUser(buyer.id(), show.scheduleId())).containsExactly("RELEASED");
        assertThat(fixtures.seatStatus(show.scheduleId(), seatId)).isEqualTo("AVAILABLE");

        // The real point of releasing: the seat is sellable again.
        TestUser other = fixtures.registerUser();
        fixtures.tryHoldSeats(other.token(), show.scheduleId(), List.of(seatId))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("payment_intent.payment_failed cancels the ticket and frees the seats")
    void paymentFailedCancelsTheTicket() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(5)));
        // The service finds the ticket through the metadata, since no payment intent is stored yet.
        deliver(StripeEvents.paymentIntentFailed("evt_test_failed", "pi_test_failed", ticketId))
                .andExpect(status().isOk());

        assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
        assertThat(reservationStatusesOfUser(user.id(), show.scheduleId())).containsExactly("RELEASED");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(5))).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("charge.refunded refunds a paid ticket and frees its seats, and is idempotent")
    void chargeRefundedRefundsTheTicket() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        long ticketId = pendingTicket(user.token(), show.scheduleId(), List.of(show.seat(1)));
        String paymentIntentId = payTicket(ticketId);

        deliver(StripeEvents.chargeRefunded("evt_test_refunded", paymentIntentId, ticketId))
                .andExpect(status().isOk());

        assertThat(ticketStatus(ticketId)).isEqualTo("REFUNDED");
        assertThat(reservationStatusesOfTicket(ticketId)).containsExactly("RELEASED");
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(1))).isEqualTo("AVAILABLE");

        deliver(StripeEvents.chargeRefunded("evt_test_refunded", paymentIntentId, ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("already REFUNDED")));
        assertThat(ticketStatus(ticketId)).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("a payment that arrives after the seats are gone is refunded in full, never kept")
    void paymentWithoutSeatsIsRefundedInsteadOfKept() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        TestUser other = fixtures.registerUser();
        long seatId = show.seat(3); // NORMAL, 10.00
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(seatId));

        // The holds disappear behind the ticket's back - clock skew, an admin, a stuck delivery.
        jdbcTemplate.update(
                "UPDATE seat_reservations SET status = 'RELEASED', expires_at = NULL "
                        + "WHERE user_id = ? AND schedule_id = ? AND status = 'HELD'",
                buyer.id(), show.scheduleId());
        // ... and somebody else takes the seat in the meantime.
        fixtures.holdSeats(other.token(), show.scheduleId(), List.of(seatId));

        String paymentIntentId = "pi_test_orphaned_payment";
        deliver(StripeEvents.checkoutSessionCompleted("evt_test_orphaned_seats",
                        FakePaymentGateway.sessionIdOf(ticketId), ticketId, paymentIntentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("refunded")));

        // The buyer is not charged for seats that are not theirs: REFUNDED, never PAID.
        assertThat(ticketStatus(ticketId)).isEqualTo("REFUNDED");
        assertThat(ticketIsPaidAtSet(ticketId)).isFalse();
        FakePaymentGateway.RecordedRefund refund = paymentGateway.refundOf(paymentIntentId).orElseThrow();
        assertThat(refund.amount()).isEqualByComparingTo(new BigDecimal("10.00"));

        // The seat stays with the user who legitimately holds it now.
        assertThat(reservationStatusesOfUser(other.id(), show.scheduleId())).containsExactly("HELD");
        assertThat(fixtures.seatStatus(show.scheduleId(), seatId)).isEqualTo("HELD");
    }

    @Test
    @DisplayName("a payment without seats and without a payment intent is left for a human, still 200")
    void paymentWithoutSeatsAndWithoutPaymentIntentIsLeftForAHuman() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser buyer = fixtures.registerUser();
        long ticketId = pendingTicket(buyer.token(), show.scheduleId(), List.of(show.seat(2)));
        jdbcTemplate.update(
                "UPDATE seat_reservations SET status = 'RELEASED', expires_at = NULL "
                        + "WHERE user_id = ? AND schedule_id = ? AND status = 'HELD'",
                buyer.id(), show.scheduleId());

        deliver(StripeEvents.checkoutSessionCompletedWithoutPaymentIntent(
                        "evt_test_orphaned_no_pi", FakePaymentGateway.sessionIdOf(ticketId), ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("manual-follow-up")));

        // Nothing could be refunded, so nothing is claimed either: the ticket is not marked PAID.
        assertThat(ticketStatus(ticketId)).isEqualTo("PENDING_PAYMENT");
        assertThat(ticketIsPaidAtSet(ticketId)).isFalse();
        assertThat(paymentGateway.refunds()).isEmpty();
    }

    @Test
    @DisplayName("an event for a ticket this server does not know is accepted and ignored")
    void unknownTicketIsIgnored() throws Exception {
        deliver(StripeEvents.checkoutSessionCompleted(
                        "evt_test_orphan", "cs_test_orphan", 9_999_999L, "pi_test_orphan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.containsString("no ticket matches")));
    }

    private String paidAtText(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT paid_at::text FROM tickets WHERE id = ?", String.class, ticketId);
    }
}

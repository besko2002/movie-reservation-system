package com.example.moviereservation.tickets;

import com.example.moviereservation.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for the phase 7 tests that need a working payment provider: the real PostgreSQL of
 * {@link TestcontainersConfiguration}, the deterministic {@link FakePaymentGateway} instead of
 * Stripe, and a webhook secret so the <em>real</em> signature verification can be exercised.
 *
 * <p>The webhook secret is set here rather than in {@code application.yml} because production must
 * keep starting without one; the tests need a known value to sign with.
 */
@SpringBootTest(properties = {
        // Only the webhook secret: app.stripe.secret-key stays empty, which proves that verification
        // and the whole webhook path work on a server that has no Stripe API credentials at all.
        "app.stripe.webhook-secret=whsec_test_0123456789abcdef"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakePaymentGatewayConfiguration.class})
abstract class AbstractTicketIntegrationTest {

    /** Webhook secret of the test context; the tests sign their deliveries with it. */
    static final String WEBHOOK_SECRET = "whsec_test_0123456789abcdef";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected FakePaymentGateway paymentGateway;

    @Value("${app.admin.email}")
    protected String adminEmail;

    @Value("${app.admin.password}")
    protected String adminPassword;

    @Value("${app.ticket.cancellation-cutoff-hours}")
    protected int cancellationCutoffHours;

    protected TicketApiFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new TicketApiFixtures(mockMvc, objectMapper, adminEmail, adminPassword);
        paymentGateway.reset();
    }

    // --- checkout ------------------------------------------------------------------------------

    /** Posts a checkout for that schedule without asserting the outcome. */
    protected ResultActions postCheckout(String token, long scheduleId) throws Exception {
        return mockMvc.perform(post("/api/tickets")
                .header("Authorization", TicketApiFixtures.bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"scheduleId":%d}""".formatted(scheduleId)));
    }

    /** Checks out and expects the 201 answer. */
    protected JsonNode checkout(String token, long scheduleId) throws Exception {
        String body = postCheckout(token, scheduleId)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Holds the given seats and checks out: the usual "ticket waiting for payment" starting point. */
    protected long pendingTicket(String token, long scheduleId, List<Long> seatIds) throws Exception {
        fixtures.holdSeats(token, scheduleId, seatIds);
        return checkout(token, scheduleId).path("ticketId").asLong();
    }

    // --- webhook -------------------------------------------------------------------------------

    /** Delivers a payload with a valid signature computed over exactly those bytes. */
    protected ResultActions deliver(String payload) throws Exception {
        return deliver(payload, StripeSignatures.valid(payload, WEBHOOK_SECRET));
    }

    /** Delivers a payload with the given (possibly bogus) signature header. */
    protected ResultActions deliver(String payload, String signatureHeader) throws Exception {
        var request = post("/api/payments/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
        if (signatureHeader != null) {
            request = request.header(PaymentWebhookController.SIGNATURE_HEADER, signatureHeader);
        }
        return mockMvc.perform(request);
    }

    /**
     * Pays a pending ticket the way production does: a genuine, correctly signed
     * {@code checkout.session.completed} delivery.
     *
     * @return the payment intent id the ticket is now paid with
     */
    protected String payTicket(long ticketId) throws Exception {
        String paymentIntentId = "pi_test_" + ticketId + "_" + UUID.randomUUID().toString().substring(0, 8);
        deliver(StripeEvents.checkoutSessionCompleted(
                        "evt_test_" + UUID.randomUUID(), FakePaymentGateway.sessionIdOf(ticketId),
                        ticketId, paymentIntentId))
                .andExpect(status().isOk());
        return paymentIntentId;
    }

    // --- database assertions -------------------------------------------------------------------

    protected String ticketStatus(long ticketId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId);
    }

    protected String ticketPaymentIntent(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT stripe_payment_intent_id FROM tickets WHERE id = ?", String.class, ticketId);
    }

    protected String ticketSessionId(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT stripe_session_id FROM tickets WHERE id = ?", String.class, ticketId);
    }

    protected boolean ticketIsPaidAtSet(long ticketId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT paid_at IS NOT NULL FROM tickets WHERE id = ?", Boolean.class, ticketId));
    }

    /** @return the statuses of the reservations stamped with that ticket, oldest first */
    protected List<String> reservationStatusesOfTicket(long ticketId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM seat_reservations WHERE ticket_id = ? ORDER BY id", String.class, ticketId);
    }

    /** @return the statuses of a user's reservations in a schedule, oldest first */
    protected List<String> reservationStatusesOfUser(long userId, long scheduleId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM seat_reservations WHERE user_id = ? AND schedule_id = ? ORDER BY id",
                String.class, userId, scheduleId);
    }

    /** @return the expiry instants of a user's HELD rows in a schedule, oldest first */
    protected List<Instant> holdExpiriesOfUser(long userId, long scheduleId) {
        return jdbcTemplate.queryForList(
                        "SELECT expires_at FROM seat_reservations "
                                + "WHERE user_id = ? AND schedule_id = ? AND status = 'HELD' ORDER BY id",
                        OffsetDateTime.class, userId, scheduleId).stream()
                .map(OffsetDateTime::toInstant)
                .toList();
    }

    protected int ticketCount(long userId, long scheduleId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tickets WHERE user_id = ? AND schedule_id = ?",
                Integer.class, userId, scheduleId);
        return count == null ? 0 : count;
    }
}

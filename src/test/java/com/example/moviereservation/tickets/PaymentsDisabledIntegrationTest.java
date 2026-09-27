package com.example.moviereservation.tickets;

import com.example.moviereservation.TestcontainersConfiguration;
import com.example.moviereservation.tickets.TicketApiFixtures.Show;
import com.example.moviereservation.tickets.TicketApiFixtures.TestUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The unconfigured server: no Stripe secret key, no webhook secret, and no fake gateway either —
 * exactly what {@code ./mvnw spring-boot:run} without environment variables gives.
 *
 * <p>What this proves is the deployment promise of phase 7: the application <strong>starts</strong>
 * and keeps serving everything that does not need a payment provider, while the endpoints that do
 * answer a clean <strong>503</strong> {@code ApiError} instead of a 500 stack trace.
 */
@SpringBootTest(properties = {
        "app.stripe.secret-key=",
        "app.stripe.webhook-secret="
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentsDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentGateway paymentGateway;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    private TicketApiFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new TicketApiFixtures(mockMvc, objectMapper, adminEmail, adminPassword);
    }

    @Test
    @DisplayName("without a secret key the gateway is the disabled one and the context still starts")
    void gatewayIsDisabled() {
        assertThat(paymentGateway.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("checkout answers 503 with an ApiError and creates no ticket")
    void checkoutIsServiceUnavailable() throws Exception {
        Show show = fixtures.createShow(fixtures.adminToken());
        TestUser user = fixtures.registerUser();
        fixtures.holdSeats(user.token(), show.scheduleId(), List.of(show.seat(0)));

        mockMvc.perform(post("/api/tickets")
                        .header("Authorization", TicketApiFixtures.bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scheduleId":%d}""".formatted(show.scheduleId())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.path").value("/api/tickets"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not configured")));

        // The whole checkout rolled back: no half-created ticket is left behind.
        Integer tickets = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tickets WHERE user_id = ?", Integer.class, user.id());
        assertThat(tickets).isZero();
        // ... and the seats are still held, so the user can pay once Stripe is configured.
        assertThat(fixtures.seatStatus(show.scheduleId(), show.seat(0))).isEqualTo("HELD");
    }

    @Test
    @DisplayName("the read endpoints keep working: /me is an empty array")
    void readEndpointsKeepWorking() throws Exception {
        TestUser user = fixtures.registerUser();

        mockMvc.perform(get("/api/tickets/me")
                        .header("Authorization", TicketApiFixtures.bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("a webhook delivery is rejected with 400 while no webhook secret is configured")
    void webhookIsRejectedWithoutSecret() throws Exception {
        String payload = StripeEvents.checkoutSessionCompleted("evt_x", "cs_x", 1L, "pi_x");

        mockMvc.perform(post("/api/payments/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(PaymentWebhookController.SIGNATURE_HEADER, "t=1,v1=deadbeef")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cannot be verified")));
    }
}

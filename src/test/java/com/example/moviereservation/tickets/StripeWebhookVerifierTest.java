package com.example.moviereservation.tickets;

import com.example.moviereservation.common.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of the signature check itself: real HMAC-SHA256 over the raw body, no Spring context
 * and no network.
 */
class StripeWebhookVerifierTest {

    private static final String SECRET = "whsec_unit_test_secret";

    private final StripeWebhookVerifier verifier =
            new StripeWebhookVerifier(SECRET, new ObjectMapper());

    private static final String PAYLOAD =
            StripeEvents.checkoutSessionCompleted("evt_1", "cs_1", 42L, "pi_1");

    @Test
    @DisplayName("a correctly signed delivery is accepted and parsed")
    void correctSignatureIsAccepted() {
        PaymentEvent event = verifier.verifyAndParse(PAYLOAD, StripeSignatures.valid(PAYLOAD, SECRET));

        assertThat(event.id()).isEqualTo("evt_1");
        assertThat(event.type()).isEqualTo(PaymentEvent.CHECKOUT_SESSION_COMPLETED);
        assertThat(event.sessionId()).isEqualTo("cs_1");
        assertThat(event.paymentIntentId()).isEqualTo("pi_1");
        assertThat(event.ticketId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a payment_intent event is parsed from its own id and metadata")
    void paymentIntentEventIsParsed() {
        String payload = StripeEvents.paymentIntentFailed("evt_2", "pi_2", 7L);

        PaymentEvent event = verifier.verifyAndParse(payload, StripeSignatures.valid(payload, SECRET));

        assertThat(event.type()).isEqualTo(PaymentEvent.PAYMENT_INTENT_PAYMENT_FAILED);
        assertThat(event.sessionId()).isNull();
        assertThat(event.paymentIntentId()).isEqualTo("pi_2");
        assertThat(event.ticketId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a charge event is parsed from its payment_intent field")
    void chargeEventIsParsed() {
        String payload = StripeEvents.chargeRefunded("evt_3", "pi_3", 9L);

        PaymentEvent event = verifier.verifyAndParse(payload, StripeSignatures.valid(payload, SECRET));

        assertThat(event.type()).isEqualTo(PaymentEvent.CHARGE_REFUNDED);
        assertThat(event.paymentIntentId()).isEqualTo("pi_3");
        assertThat(event.ticketId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("one edited byte in the body invalidates the signature")
    void tamperedPayloadIsRejected() {
        String signature = StripeSignatures.valid(PAYLOAD, SECRET);
        String tampered = PAYLOAD.replace("\"amount_total\":3500", "\"amount_total\":1");

        assertThatThrownBy(() -> verifier.verifyAndParse(tampered, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("a signature made with another secret is rejected")
    void wrongSecretIsRejected() {
        String signature = StripeSignatures.valid(PAYLOAD, "whsec_some_other_secret");

        assertThatThrownBy(() -> verifier.verifyAndParse(PAYLOAD, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("a missing or malformed header is rejected")
    void missingOrMalformedHeaderIsRejected() {
        assertThatThrownBy(() -> verifier.verifyAndParse(PAYLOAD, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Missing Stripe-Signature");

        assertThatThrownBy(() -> verifier.verifyAndParse(PAYLOAD, "v1=abc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no timestamp");

        assertThatThrownBy(() -> verifier.verifyAndParse(PAYLOAD, "t=not-a-number,v1=abc"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unreadable timestamp");
    }

    @Test
    @DisplayName("a correctly signed but old delivery is rejected (replay protection)")
    void staleTimestampIsRejected() {
        long longAgo = Instant.now().minusSeconds(StripeWebhookVerifier.TOLERANCE.toSeconds() + 60).getEpochSecond();
        String signature = StripeSignatures.signedAt(PAYLOAD, SECRET, longAgo);

        assertThatThrownBy(() -> verifier.verifyAndParse(PAYLOAD, signature))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    @DisplayName("a verified but unreadable body is rejected")
    void unreadableBodyIsRejected() {
        String notJson = "this is not json";

        assertThatThrownBy(() -> verifier.verifyAndParse(notJson, StripeSignatures.valid(notJson, SECRET)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unreadable");
    }

    @Test
    @DisplayName("without a configured secret nothing can be verified: 400, never silent acceptance")
    void withoutSecretEverythingIsRejected() {
        StripeWebhookVerifier unconfigured = new StripeWebhookVerifier("  ", new ObjectMapper());

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.verifyAndParse(PAYLOAD, StripeSignatures.valid(PAYLOAD, SECRET)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be verified");
    }
}

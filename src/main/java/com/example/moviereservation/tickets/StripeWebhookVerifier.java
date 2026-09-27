package com.example.moviereservation.tickets;

import com.example.moviereservation.common.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Turns a raw webhook delivery into a verified {@link PaymentEvent}: first the signature, then the
 * JSON.
 *
 * <h2>Why this is not part of {@link PaymentGateway}</h2>
 * Verifying a delivery needs the <em>webhook</em> secret only, never the API secret key. Keeping it
 * in its own always-present bean means:
 * <ul>
 *   <li>a server configured with only {@code app.stripe.webhook-secret} (no
 *       {@code app.stripe.secret-key}) still authenticates deliveries correctly — payments are
 *       disabled, but a delivery is never mistaken for a forgery or accepted as one;</li>
 *   <li>the integration tests exercise this real HMAC-SHA256 code path with a test secret, instead
 *       of a fake that "verifies" nothing;</li>
 *   <li>nothing here touches the network or the {@code stripe-java} HTTP stack.</li>
 * </ul>
 *
 * <h2>Signature scheme</h2>
 * The {@code Stripe-Signature} header is a comma separated list of {@code key=value} pairs:
 * {@code t=<unix seconds>,v1=<hex hmac>[,v1=<hex hmac>...]}. The expected value is the
 * HMAC-SHA256 of the string {@code "<t>.<raw body>"} keyed with the webhook secret, hex encoded.
 * Comparison is constant time, and a timestamp further away than {@link #TOLERANCE} is rejected so
 * a captured delivery cannot be replayed later.
 *
 * <p>Every failure is a {@link BadRequestException}, which the
 * {@link com.example.moviereservation.common.GlobalExceptionHandler} renders as a 400 with an
 * {@link com.example.moviereservation.common.ApiError} body. A missing webhook secret is a 400 as
 * well, on purpose: a server that cannot authenticate a delivery must not let it change any state,
 * and 400 (rather than 503) is what keeps Stripe from retrying a delivery this server will never
 * be able to accept.
 */
@Component
public class StripeWebhookVerifier {

    /** How far the signed timestamp may be from now, matching Stripe's own default. */
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static final String ALGORITHM = "HmacSHA256";
    private static final String TIMESTAMP_KEY = "t";
    private static final String SIGNATURE_KEY = "v1";

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookVerifier.class);

    private final String webhookSecret;
    private final ObjectMapper objectMapper;

    StripeWebhookVerifier(@Value("${app.stripe.webhook-secret:}") String webhookSecret,
                          ObjectMapper objectMapper) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
    }

    /** @return whether a webhook secret is configured, so deliveries can be authenticated at all */
    public boolean isConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /**
     * Verifies a delivery and maps it to this project's event shape.
     *
     * @param payload         raw request body, byte for byte as it was received; any
     *                        reserialisation would break the signature
     * @param signatureHeader value of the {@code Stripe-Signature} header
     * @return the verified event
     * @throws BadRequestException when no webhook secret is configured, or the header is missing,
     *                            malformed, stale or does not match the payload, or the body is not
     *                            a readable event
     */
    public PaymentEvent verifyAndParse(String payload, String signatureHeader) {
        verify(payload, signatureHeader);
        return parse(payload);
    }

    private void verify(String payload, String signatureHeader) {
        if (!isConfigured()) {
            log.warn("Rejected a webhook delivery: app.stripe.webhook-secret is not configured");
            throw new BadRequestException("Webhook deliveries cannot be verified: "
                    + "this server has no Stripe webhook secret configured");
        }
        if (payload == null || payload.isEmpty()) {
            throw new BadRequestException("Webhook delivery has an empty body");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new BadRequestException("Missing Stripe-Signature header");
        }

        String timestamp = null;
        boolean matched = false;
        for (String part : signatureHeader.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (TIMESTAMP_KEY.equals(key)) {
                timestamp = value;
            }
        }
        if (timestamp == null) {
            throw new BadRequestException("Malformed Stripe-Signature header: no timestamp");
        }
        requireFreshTimestamp(timestamp);

        String expected = hmacSha256Hex(timestamp + "." + payload);
        for (String part : signatureHeader.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (SIGNATURE_KEY.equals(part.substring(0, separator).trim())
                    && constantTimeEquals(expected, part.substring(separator + 1).trim())) {
                matched = true;
            }
        }
        if (!matched) {
            log.warn("Rejected a webhook delivery whose signature does not match the payload");
            throw new BadRequestException("Invalid Stripe webhook signature");
        }
    }

    private static void requireFreshTimestamp(String timestamp) {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Malformed Stripe-Signature header: unreadable timestamp");
        }
        Duration age = Duration.between(Instant.ofEpochSecond(seconds), Instant.now()).abs();
        if (age.compareTo(TOLERANCE) > 0) {
            log.warn("Rejected a webhook delivery signed {}s away from now", age.toSeconds());
            throw new BadRequestException("Stripe-Signature timestamp is outside the tolerance zone");
        }
    }

    private String hmacSha256Hex(String signedPayload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            // HmacSHA256 is mandatory on every JVM, and the key is a non-empty byte array here.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the handful of fields this module acts on out of the event JSON with Jackson.
     *
     * <p>Deliberately not {@code Event.GSON}/{@code StripeObject} deserialisation: the payload is
     * already verified and only five values are needed, so the module stays independent of the
     * SDK's model classes and the tests can post a hand written event.
     */
    private PaymentEvent parse(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Unreadable Stripe webhook payload: not valid JSON");
        }
        String id = text(root, "id");
        String type = text(root, "type");
        if (type == null) {
            throw new BadRequestException("Unreadable Stripe webhook payload: no event type");
        }

        JsonNode object = root.path("data").path("object");
        String objectType = text(object, "object");
        String objectId = text(object, "id");

        String sessionId = "checkout.session".equals(objectType) || type.startsWith("checkout.session.")
                ? objectId
                : null;
        String paymentIntentId = "payment_intent".equals(objectType)
                ? objectId
                : text(object, "payment_intent");

        Long ticketId = parseTicketId(text(object, "client_reference_id"));
        if (ticketId == null) {
            ticketId = parseTicketId(text(object.path("metadata"), "ticketId"));
        }
        return new PaymentEvent(id, type, sessionId, paymentIntentId, ticketId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Long parseTicketId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            // A reference this server did not write: the event is still valid, it just has no
            // ticket id, and the service falls back to the session / payment intent lookup.
            return null;
        }
    }
}

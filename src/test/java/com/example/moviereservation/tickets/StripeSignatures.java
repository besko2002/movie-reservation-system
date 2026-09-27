package com.example.moviereservation.tickets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Builds real {@code Stripe-Signature} headers for the webhook tests.
 *
 * <p>This is an independent second implementation of the scheme on purpose: the header is
 * {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<raw body>">}, computed here with plain JCE
 * and verified on the server by {@link StripeWebhookVerifier}. A test that passes therefore proves
 * the production verification really computes the HMAC — it is not a stub agreeing with itself.
 */
final class StripeSignatures {

    private static final String ALGORITHM = "HmacSHA256";

    private StripeSignatures() {
    }

    /** @return a valid header for that payload, signed now */
    static String valid(String payload, String secret) {
        return signedAt(payload, secret, Instant.now().getEpochSecond());
    }

    /** @return a valid header for that payload, signed at the given unix second */
    static String signedAt(String payload, String secret, long timestamp) {
        return "t=%d,v1=%s".formatted(timestamp, hmacHex(timestamp + "." + payload, secret));
    }

    /**
     * A header whose HMAC was computed over a different body: what an attacker replaying a genuine
     * delivery with an edited payload produces.
     *
     * @param signedPayload body the signature was computed over
     * @param secret        webhook secret
     * @return the header to send together with the tampered body
     */
    static String tamperedFor(String signedPayload, String secret) {
        return valid(signedPayload, secret);
    }

    /** @return a syntactically valid header whose signature is simply wrong */
    static String wrongSignature() {
        return "t=%d,v1=%s".formatted(Instant.now().getEpochSecond(), "0".repeat(64));
    }

    private static String hmacHex(String signedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 must be available in tests", ex);
        }
    }
}

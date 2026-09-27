package com.example.moviereservation.tickets;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic {@link PaymentGateway} used by every ticket integration test.
 *
 * <p>It exists so the test suite can exercise the complete checkout, webhook and refund flow
 * <strong>without a single network call and without Stripe credentials</strong>: ids are derived
 * from the ticket id, so a test can predict them, and every refund is recorded so a test can assert
 * that the right amount was actually asked for.
 *
 * <p>Note what is <em>not</em> faked: webhook signature verification. That happens in the
 * production {@link StripeWebhookVerifier} with a real HMAC-SHA256 over the raw body, so the tests
 * cover the real verification code instead of a stub that accepts everything.
 */
class FakePaymentGateway implements PaymentGateway {

    /** Prefix of the generated session ids, mirroring Stripe's {@code cs_test_...} shape. */
    static final String SESSION_PREFIX = "cs_test_fake_";

    /** Base of the generated payment URLs; deliberately not a real host. */
    static final String CHECKOUT_URL_PREFIX = "https://checkout.test.invalid/pay/";

    /** One refund the production code asked for. */
    record RecordedRefund(String paymentIntentId, BigDecimal amount) {
    }

    private final Map<String, PaymentSessionDto> sessionsById = new ConcurrentHashMap<>();
    private final List<CheckoutSessionRequest> checkoutRequests = new ArrayList<>();
    private final List<RecordedRefund> refunds = new ArrayList<>();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public synchronized PaymentSessionDto createCheckoutSession(CheckoutSessionRequest request) {
        checkoutRequests.add(request);
        String sessionId = SESSION_PREFIX + request.ticketId();
        // Stripe stores the expires_at it is given verbatim, so the fake echoes it too; a request
        // without one falls back to Stripe's own minimum window.
        OffsetDateTime expiresAt = request.expiresAt() != null
                ? request.expiresAt()
                : OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        PaymentSessionDto session = new PaymentSessionDto(sessionId, CHECKOUT_URL_PREFIX + sessionId, expiresAt);
        sessionsById.put(sessionId, session);
        return session;
    }

    @Override
    public PaymentSessionDto retrieveCheckoutSession(String sessionId) {
        PaymentSessionDto known = sessionsById.get(sessionId);
        if (known != null) {
            return known;
        }
        // A session created by an earlier test context: still answer deterministically instead of
        // failing, because "the same page comes back" is what the caller is testing.
        return new PaymentSessionDto(sessionId, CHECKOUT_URL_PREFIX + sessionId, null);
    }

    @Override
    public synchronized RefundDto refund(String paymentIntentId, BigDecimal amount) {
        refunds.add(new RecordedRefund(paymentIntentId, amount));
        return new RefundDto("re_test_fake_" + refunds.size(), paymentIntentId, amount, "succeeded");
    }

    // --- test inspection ---------------------------------------------------------------------

    synchronized void reset() {
        sessionsById.clear();
        checkoutRequests.clear();
        refunds.clear();
    }

    synchronized List<RecordedRefund> refunds() {
        return List.copyOf(refunds);
    }

    /** @return the refund recorded for that payment intent, if the code asked for one */
    synchronized Optional<RecordedRefund> refundOf(String paymentIntentId) {
        return refunds.stream()
                .filter(refund -> refund.paymentIntentId().equals(paymentIntentId))
                .findFirst();
    }

    /** @return the checkout session request the service sent for that ticket */
    synchronized Optional<CheckoutSessionRequest> checkoutRequestFor(long ticketId) {
        return checkoutRequests.stream()
                .filter(request -> request.ticketId() != null && request.ticketId() == ticketId)
                .findFirst();
    }

    synchronized int checkoutSessionCount() {
        return checkoutRequests.size();
    }

    static String sessionIdOf(long ticketId) {
        return SESSION_PREFIX + ticketId;
    }
}

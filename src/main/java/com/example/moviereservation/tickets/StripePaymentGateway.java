package com.example.moviereservation.tickets;

import com.example.moviereservation.common.ServiceUnavailableException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;

/**
 * The real {@link PaymentGateway}: Stripe Checkout Sessions and Stripe Refunds through the
 * {@code stripe-java} SDK.
 *
 * <p><strong>This is the only class in the project that talks to Stripe over the network.</strong>
 * It is created by {@link PaymentGatewayConfig} only when {@code app.stripe.secret-key} is a
 * non-blank value, so a build or a test run without credentials can never open a network call: it
 * gets {@link DisabledPaymentGateway} instead.
 *
 * <p>Webhook deliveries are handled by {@link StripeWebhookVerifier} rather than here, because
 * verifying and reading a delivery needs no API credentials and no HTTP call at all.
 *
 * <p>Failures of the SDK are translated, never leaked: a failed API call becomes
 * {@link ServiceUnavailableException} (503) — the request was fine, the provider was not.
 */
class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    /** Currencies Stripe expects without a minor unit (amounts are whole numbers). */
    private static final int DEFAULT_FRACTION_DIGITS = 2;

    private final StripeClient client;
    private final String currency;
    private final String baseUrl;

    StripePaymentGateway(StripeClient client, String currency, String baseUrl) {
        this.client = client;
        this.currency = currency.toLowerCase();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public PaymentSessionDto createCheckoutSession(CheckoutSessionRequest request) {
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(baseUrl + "/api/tickets/" + request.ticketId() + "?payment=success")
                .setCancelUrl(baseUrl + "/api/tickets/" + request.ticketId() + "?payment=cancelled")
                // Travels back on every event of this session, so the webhook can find the ticket
                // even when the metadata is lost.
                .setClientReferenceId(String.valueOf(request.ticketId()))
                .putMetadata("ticketId", String.valueOf(request.ticketId()))
                .putMetadata("userId", String.valueOf(request.userId()))
                .putMetadata("scheduleId", String.valueOf(request.scheduleId()));

        if (request.expiresAt() != null) {
            // Without this Stripe keeps the page payable for 24 hours, far longer than the seat
            // holds behind it: the buyer could pay for seats somebody else had already taken.
            // The instant is exactly the one the holds were extended to. Stripe wants epoch
            // seconds and accepts 30 minutes .. 24 hours from now; TicketCheckoutWindow validates
            // that range at startup.
            params.setExpiresAt(request.expiresAt().toEpochSecond());
        }

        for (CheckoutLineItem item : request.lineItems()) {
            params.addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(currency)
                            .setUnitAmount(toMinorUnits(item.amount()))
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(item.name())
                                    .setDescription(item.description())
                                    .build())
                            .build())
                    .build());
        }

        try {
            Session session = client.checkout().sessions().create(params.build());
            log.info("Created Stripe Checkout Session {} for ticket {}", session.getId(), request.ticketId());
            return new PaymentSessionDto(session.getId(), session.getUrl(), toOffsetDateTime(session.getExpiresAt()));
        } catch (StripeException ex) {
            log.error("Stripe refused to create a Checkout Session for ticket {}", request.ticketId(), ex);
            throw new ServiceUnavailableException(
                    "The payment provider could not create a checkout session right now; please try again");
        }
    }

    @Override
    public RefundDto refund(String paymentIntentId, BigDecimal amount) {
        try {
            Refund refund = client.refunds().create(RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setAmount(toMinorUnits(amount))
                    .build());
            log.info("Created Stripe refund {} of {} {} for payment intent {}",
                    refund.getId(), amount, currency, paymentIntentId);
            return new RefundDto(refund.getId(), paymentIntentId, amount, refund.getStatus());
        } catch (StripeException ex) {
            log.error("Stripe refused to refund payment intent {}", paymentIntentId, ex);
            throw new ServiceUnavailableException(
                    "The payment provider could not process the refund right now; please try again");
        }
    }

    @Override
    public PaymentSessionDto retrieveCheckoutSession(String sessionId) {
        try {
            Session session = client.checkout().sessions().retrieve(sessionId);
            return new PaymentSessionDto(session.getId(), session.getUrl(), toOffsetDateTime(session.getExpiresAt()));
        } catch (StripeException ex) {
            log.error("Stripe refused to read back Checkout Session {}", sessionId, ex);
            throw new ServiceUnavailableException(
                    "The payment provider could not be reached right now; please try again");
        }
    }

    /**
     * Stripe charges in the currency's smallest unit (cents for USD, whole units for JPY), so the
     * number of fraction digits comes from the currency itself instead of a hard-coded 100.
     */
    private long toMinorUnits(BigDecimal amount) {
        int fractionDigits;
        try {
            fractionDigits = Currency.getInstance(currency.toUpperCase()).getDefaultFractionDigits();
        } catch (IllegalArgumentException ex) {
            fractionDigits = DEFAULT_FRACTION_DIGITS;
        }
        if (fractionDigits < 0) {
            fractionDigits = DEFAULT_FRACTION_DIGITS;
        }
        return amount.setScale(fractionDigits, RoundingMode.HALF_UP).movePointRight(fractionDigits).longValueExact();
    }

    private static OffsetDateTime toOffsetDateTime(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC);
    }
}

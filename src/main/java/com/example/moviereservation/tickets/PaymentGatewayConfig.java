package com.example.moviereservation.tickets;

import com.stripe.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link PaymentGateway} implementation at startup.
 *
 * <p>The decision is a single explicit branch instead of two conditional beans on purpose: exactly
 * one {@code PaymentGateway} bean always exists, whichever way the branch goes, so nothing in the
 * application can be left without a payment port and no bean-ordering subtlety can change which
 * implementation wins.
 *
 * <ul>
 *   <li>{@code app.stripe.secret-key} non-blank &rarr; {@link StripePaymentGateway}, the only class
 *       that talks to Stripe.</li>
 *   <li>blank (the default, and what every test and the plain {@code mvnw spring-boot:run} uses)
 *       &rarr; {@link DisabledPaymentGateway}: the application starts normally and the endpoints
 *       that need a provider answer 503.</li>
 * </ul>
 *
 * <p>Integration tests bind their own deterministic fake as {@code @Primary}, so they never reach
 * either of these.
 */
@Configuration
class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    PaymentGateway paymentGateway(@Value("${app.stripe.secret-key:}") String secretKey,
                                  @Value("${app.stripe.currency:usd}") String currency,
                                  @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("No Stripe secret key configured: payments are disabled. "
                    + "POST /api/tickets and refunds will answer 503 until STRIPE_SECRET_KEY is set.");
            return new DisabledPaymentGateway();
        }
        log.info("Stripe payments enabled (currency={}, baseUrl={})", currency, baseUrl);
        return new StripePaymentGateway(new StripeClient(secretKey), currency, baseUrl);
    }
}

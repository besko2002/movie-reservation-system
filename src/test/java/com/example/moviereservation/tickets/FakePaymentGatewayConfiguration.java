package com.example.moviereservation.tickets;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Binds {@link FakePaymentGateway} as the {@code @Primary} {@link PaymentGateway}, so the ticket
 * integration tests never reach {@code StripePaymentGateway} (and therefore never the network).
 *
 * <p>{@code PaymentGatewayConfig} still publishes its own bean - with an empty
 * {@code app.stripe.secret-key} that is {@code DisabledPaymentGateway}, which makes no calls
 * either. {@code @Primary} decides which one gets injected.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakePaymentGatewayConfiguration {

    @Bean
    @Primary
    FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }
}

package com.example.moviereservation.tickets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TicketCheckoutWindow}: the payment window and its startup validation.
 *
 * <p>Deliberately an {@link ApplicationContextRunner} test and not a {@code @SpringBootTest}: what
 * is being asserted is that a bad {@code app.ticket.checkout-minutes} stops the context from
 * starting, and that needs a context that is cheap to fail — no database, no Testcontainers.
 */
class TicketCheckoutWindowTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TicketCheckoutWindow.class);

    @Test
    @DisplayName("the default of 30 minutes is accepted")
    void defaultValueStarts() {
        contextRunner.withPropertyValues("app.ticket.checkout-minutes=30").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TicketCheckoutWindow.class).getMinutes()).isEqualTo(30);
        });
    }

    @Test
    @DisplayName("the upper bound of 24 hours is accepted")
    void upperBoundStarts() {
        contextRunner.withPropertyValues("app.ticket.checkout-minutes=1440").run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a window below Stripe's 30 minute minimum makes the context fail to start")
    void tooShortWindowFailsFast() {
        contextRunner.withPropertyValues("app.ticket.checkout-minutes=10").run(context ->
                assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("app.ticket.checkout-minutes"));
    }

    @Test
    @DisplayName("a window above Stripe's 24 hour maximum makes the context fail to start")
    void tooLongWindowFailsFast() {
        contextRunner.withPropertyValues("app.ticket.checkout-minutes=1441").run(context ->
                assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("app.ticket.checkout-minutes"));
    }

    @Test
    @DisplayName("expiryFrom is now + the window, truncated to whole seconds like Stripe's expires_at")
    void expiryFromAddsTheWindow() {
        TicketCheckoutWindow window = new TicketCheckoutWindow(45);
        OffsetDateTime now = OffsetDateTime.of(2030, 1, 1, 10, 0, 0, 123_456_789, ZoneOffset.UTC);

        assertThat(window.expiryFrom(now))
                .isEqualTo(now.plusMinutes(45).truncatedTo(ChronoUnit.SECONDS));
    }
}

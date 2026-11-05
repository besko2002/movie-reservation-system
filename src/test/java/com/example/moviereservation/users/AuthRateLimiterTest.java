package com.example.moviereservation.users;

import com.example.moviereservation.common.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    @Test
    void failedLoginsArePerIpAndEmailAndAValidLoginClearsTheLimit() {
        AuthRateLimiter limiter = new AuthRateLimiter(2, 2, Duration.ofMinutes(1), Clock.systemUTC());
        limiter.failedLogin("1.2.3.4", "  USER@EXAMPLE.COM  ");
        limiter.failedLogin("1.2.3.4", "user@example.com");
        assertThatThrownBy(() -> limiter.checkLogin("1.2.3.4", "user@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.checkLogin("5.6.7.8", "user@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLogin("1.2.3.4", "other@example.com")).doesNotThrowAnyException();
        limiter.successfulLogin("1.2.3.4", "user@example.com");
        assertThatCode(() -> limiter.checkLogin("1.2.3.4", "user@example.com")).doesNotThrowAnyException();
    }

    @Test
    void registrationsArePerIpAndExpireAfterWindow() {
        MutableClock clock = new MutableClock();
        AuthRateLimiter limiter = new AuthRateLimiter(2, 2, Duration.ofSeconds(60), clock);
        limiter.registration("1.2.3.4");
        limiter.registration("1.2.3.4");
        assertThatThrownBy(() -> limiter.registration("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.registration("5.6.7.8")).doesNotThrowAnyException();
        clock.advance(Duration.ofSeconds(61));
        assertThatCode(() -> limiter.registration("1.2.3.4")).doesNotThrowAnyException();
    }

    private static class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

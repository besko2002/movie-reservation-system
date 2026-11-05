package com.example.moviereservation.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The production profile must not accept the secrets that are published in this repository. */
class ProductionSecretsGuardTest {

    @Test
    @DisplayName("the prod profile refuses the published JWT secret")
    void prodRejectsPublishedJwtSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionSecretsGuard(environment,
                ProductionSecretsGuard.DEVELOPMENT_JWT_SECRET, "a-real-admin-password", "a-real-db-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("the prod profile refuses the .env.example JWT placeholder too")
    void prodRejectsExampleJwtSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionSecretsGuard(environment,
                ProductionSecretsGuard.EXAMPLE_JWT_SECRET, "a-real-admin-password", "a-real-db-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("the prod profile refuses the published admin password")
    void prodRejectsPublishedAdminPassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionSecretsGuard(environment,
                "a-real-jwt-secret-that-is-long-enough-for-hs256", ProductionSecretsGuard.DEVELOPMENT_ADMIN_PASSWORD,
                "a-real-db-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("local development and tests keep their documented defaults")
    void devAllowsPublishedDefaults() {
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> new ProductionSecretsGuard(environment,
                ProductionSecretsGuard.DEVELOPMENT_JWT_SECRET, ProductionSecretsGuard.DEVELOPMENT_ADMIN_PASSWORD,
                "movie"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the prod profile accepts real secrets")
    void prodAcceptsRealSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatCode(() -> new ProductionSecretsGuard(environment,
                "a-real-jwt-secret-that-is-long-enough-for-hs256", "a-real-admin-password", "a-real-db-password"))
                .doesNotThrowAnyException();
    }

    @Test
    void prodRejectsPublishedDatabasePassword() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ProductionSecretsGuard(environment,
                "a-real-jwt-secret-that-is-long-enough-for-hs256", "a-real-admin-password", "movie"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }
}

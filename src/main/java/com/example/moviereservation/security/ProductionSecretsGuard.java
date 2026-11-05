package com.example.moviereservation.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Refuses to start a production deployment with the development credentials that are published in
 * this repository.
 *
 * <p>The defaults exist so a fresh clone can run with {@code ./mvnw spring-boot:run} and so the
 * compose demo starts without a configuration file. They must never protect a real deployment: a
 * JWT signed with the published key would let anyone mint an ADMIN token, and the published admin
 * password would hand over the seeded account.
 *
 * <p>The guard is deliberately tied to the {@code prod} profile rather than failing on every
 * startup: local development and the test suite keep working unchanged. Production deployments
 * must set {@code SPRING_PROFILES_ACTIVE=prod} (or {@code spring.profiles.active=prod}) and provide
 * {@code JWT_SECRET} and {@code ADMIN_PASSWORD}.
 */
@Component
public class ProductionSecretsGuard {

    /** The JWT secret published in {@code application.yml}, {@code docker-compose.yml} and the README. */
    static final String DEVELOPMENT_JWT_SECRET =
            "dev-only-insecure-secret-change-me-movie-reservation-system-0123456789";

    /** The placeholder copied by {@code .env.example}; also public and therefore unusable in production. */
    static final String EXAMPLE_JWT_SECRET = "change-me-to-a-long-random-base64-secret";

    /** The admin password published in {@code application.yml}, {@code docker-compose.yml} and the README. */
    static final String DEVELOPMENT_ADMIN_PASSWORD = "Admin@12345";

    private static final Set<String> PUBLISHED_JWT_SECRETS =
            Set.of(DEVELOPMENT_JWT_SECRET, EXAMPLE_JWT_SECRET);

    public ProductionSecretsGuard(Environment environment,
                                   @Value("${app.jwt.secret}") String jwtSecret,
                                   @Value("${app.admin.password}") String adminPassword,
                                   @Value("${spring.datasource.password}") String databasePassword) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        if (PUBLISHED_JWT_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile while app.jwt.secret is a published "
                            + "development secret; set JWT_SECRET to a long random value");
        }
        if (DEVELOPMENT_ADMIN_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile while app.admin.password is the published "
                            + "development password; set ADMIN_PASSWORD to a unique value");
        }
        if ("movie".equals(databasePassword)) {
            throw new IllegalStateException(
                    "Refusing to start with the 'prod' profile while DB_PASSWORD is the published demo password");
        }
    }
}

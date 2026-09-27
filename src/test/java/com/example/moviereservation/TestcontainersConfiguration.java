package com.example.moviereservation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared PostgreSQL container wired into Spring Boot through {@link ServiceConnection}. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Not a leak: Spring owns the container bean and stops it when the test context closes.
    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withDatabaseName("movie_reservation")
                .withUsername("movie")
                .withPassword("movie");
    }
}

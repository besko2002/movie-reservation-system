package com.example.moviereservation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} background jobs.
 *
 * <p>Phase 6 needs exactly one: the seat reservation module's expiry sweeper
 * ({@code SeatReservationExpirySweeper}), which runs every {@code app.reservation.sweep-ms}
 * milliseconds.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

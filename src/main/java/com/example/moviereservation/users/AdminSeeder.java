package com.example.moviereservation.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the administrator account at startup when it does not exist yet.
 * Idempotent: on every later start the existing admin is left untouched.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserService userService;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public AdminSeeder(UserService userService,
                       @Value("${app.admin.email}") String adminEmail,
                       @Value("${app.admin.password}") String adminPassword,
                       @Value("${app.admin.name}") String adminName) {
        this.userService = userService;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Runs the seeding step; safe to call repeatedly. */
    public void seed() {
        boolean created = userService.createAdminIfAbsent(adminName, adminEmail, adminPassword);
        if (created) {
            log.info("Seeded ADMIN account for {}", adminEmail);
        } else {
            log.debug("ADMIN account for {} already exists; nothing to seed", adminEmail);
        }
    }
}

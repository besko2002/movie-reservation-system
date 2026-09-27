package com.example.moviereservation.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test-only endpoint under {@code /api/admin/**} used to prove the role rule of
 * {@code SecurityConfig}: ADMIN gets 200, an authenticated USER gets 403.
 * Phase 2 ships no real admin endpoint yet.
 */
@RestController
public class AdminPingTestController {

    @GetMapping("/api/admin/test-ping")
    public Map<String, String> ping() {
        return Map.of("status", "pong");
    }

    /** Outside {@code /api/admin/**}: guarded only by method security. */
    @GetMapping("/api/test-method-security")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> methodSecured() {
        return Map.of("status", "pong");
    }
}

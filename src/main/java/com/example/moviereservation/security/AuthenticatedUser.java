package com.example.moviereservation.security;

/**
 * Principal stored in the {@code SecurityContext} for an authenticated request.
 *
 * @param id    user id (JWT {@code sub})
 * @param email user email
 * @param role  role name without the {@code ROLE_} prefix, e.g. {@code USER}
 */
public record AuthenticatedUser(Long id, String email, String role) {

    /** Spring Security authority derived from the role, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + role;
    }
}

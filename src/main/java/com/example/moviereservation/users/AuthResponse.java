package com.example.moviereservation.users;

/**
 * Successful authentication result.
 *
 * @param accessToken signed JWT
 * @param tokenType   always {@code Bearer}
 * @param expiresIn   token lifetime in seconds
 * @param user        the authenticated user
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserDto user) {

    static AuthResponse of(String accessToken, long expiresIn, UserDto user) {
        return new AuthResponse(accessToken, "Bearer", expiresIn, user);
    }
}

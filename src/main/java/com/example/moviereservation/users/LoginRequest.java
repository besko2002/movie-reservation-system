package com.example.moviereservation.users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * @param email    email address, matched case-insensitively
 * @param password raw password
 */
public record LoginRequest(

        @NotBlank(message = "email must not be blank")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(max = 72, message = "password must be at most 72 characters")
        String password
) {

    /** Trims the email (the password is left byte-for-byte as sent). */
    public LoginRequest {
        email = email == null ? null : email.trim();
    }
}

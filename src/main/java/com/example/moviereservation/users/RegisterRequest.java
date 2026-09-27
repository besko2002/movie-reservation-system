package com.example.moviereservation.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. The created account always gets {@link Role#USER}.
 *
 * @param name     display name
 * @param email    email address, stored trimmed and lower cased
 * @param password raw password, hashed with BCrypt before storage
 */
public record RegisterRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a valid email address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        String password
) {

    /**
     * Trims the email before validation so a copy-pasted "&nbsp;user@example.com&nbsp;" is accepted
     * instead of failing {@link Email}; the password is left byte-for-byte as sent.
     */
    public RegisterRequest {
        email = email == null ? null : email.trim();
    }
}

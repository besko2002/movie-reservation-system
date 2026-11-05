package com.example.moviereservation.users;

import com.example.moviereservation.security.AuthenticatedUser;
import org.springframework.stereotype.Service;

/** Reads current account state for every signed JWT; old role claims and revoked tokens cannot authorize. */
@Service
public class TokenAccountValidator {

    private final UserRepository users;

    public TokenAccountValidator(UserRepository users) {
        this.users = users;
    }

    public boolean isCurrent(AuthenticatedUser principal) {
        return users.findById(principal.id())
                .filter(User::isEnabled)
                .filter(user -> user.getTokenVersion() == principal.tokenVersion())
                .filter(user -> user.getRole().name().equals(principal.role()))
                .filter(user -> user.getEmail().equals(principal.email()))
                .isPresent();
    }
}

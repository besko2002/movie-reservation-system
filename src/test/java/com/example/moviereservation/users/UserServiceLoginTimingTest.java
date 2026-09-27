package com.example.moviereservation.users;

import com.example.moviereservation.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Login must cost one password comparison whether or not the email exists (no timing-based enumeration). */
class UserServiceLoginTimingTest {

    @Test
    void unknownEmailStillRunsAPasswordComparison() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$10$dummy");
        when(repository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        UserService service = new UserService(repository, encoder, mock(JwtService.class));

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(BadCredentialsException.class);

        verify(encoder, times(1)).matches("whatever", "$2a$10$dummy");
    }
}

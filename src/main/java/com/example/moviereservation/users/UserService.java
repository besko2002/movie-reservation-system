package com.example.moviereservation.users;

import com.example.moviereservation.common.BadRequestException;
import com.example.moviereservation.common.ConflictException;
import com.example.moviereservation.common.ResourceNotFoundException;
import com.example.moviereservation.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Public API of the users module: registration, login and user lookup.
 *
 * <p>Other modules must depend on this service, never on {@code UserRepository}.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Deliberately identical for "unknown email" and "wrong password" so the API leaks nothing. */
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    /** BCrypt only uses the first 72 bytes and Spring Security rejects anything longer. */
    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /** Checked against when the email is unknown, so both failure paths cost one BCrypt comparison. */
    private final String dummyPasswordHash;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing-equalisation");
    }

    /** Creates a {@link Role#USER} account and returns a freshly issued token. */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (exceedsBcryptLimit(request.password())) {
            // @Size counts characters; non-ASCII passwords (e.g. Arabic) can be <= 72 chars but > 72 bytes.
            throw new BadRequestException("password must be at most %d bytes".formatted(BCRYPT_MAX_PASSWORD_BYTES));
        }
        String email = normaliseEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }
        User user = new User(request.name().trim(), email, passwordEncoder.encode(request.password()), Role.USER);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Unique index is the final arbiter when two registrations race.
            throw new ConflictException("Email is already registered");
        }
        log.info("Registered user id={} role={}", user.getId(), user.getRole());
        return issueToken(user);
    }

    /** Verifies credentials and returns a token, or fails with a generic 401 message. */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Optional<User> user = userRepository.findByEmail(normaliseEmail(request.email()));
        // Always run one BCrypt comparison, even for an unknown email, so response time does not
        // reveal which emails are registered.
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = !exceedsBcryptLimit(request.password())
                && passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        return issueToken(user.get());
    }

    /** @throws ResourceNotFoundException when no user has that id */
    @Transactional(readOnly = true)
    public UserDto getById(Long id) {
        return userRepository.findById(id)
                .map(UserDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    /** @return the user with that email, if any */
    @Transactional(readOnly = true)
    public Optional<UserDto> findByEmail(String email) {
        return userRepository.findByEmail(normaliseEmail(email)).map(UserDto::from);
    }

    /**
     * Creates the administrator account when its email is not taken yet. Idempotent: running it
     * again on an existing database changes nothing.
     *
     * @return {@code true} when a new admin was created
     */
    @Transactional
    public boolean createAdminIfAbsent(String name, String email, String rawPassword) {
        String normalisedEmail = normaliseEmail(email);
        if (userRepository.existsByEmail(normalisedEmail)) {
            return false;
        }
        try {
            userRepository.saveAndFlush(
                    new User(name.trim(), normalisedEmail, passwordEncoder.encode(rawPassword), Role.ADMIN));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    private AuthResponse issueToken(User user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return AuthResponse.of(token, jwtService.getExpirationSeconds(), UserDto.from(user));
    }

    private static boolean exceedsBcryptLimit(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
    }

    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

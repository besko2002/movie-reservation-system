package com.example.moviereservation.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates the HS256 access tokens used by the API.
 *
 * <p>Token payload: {@code sub} = user id, {@code email}, {@code role}, plus {@code iat}/{@code exp}.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** HS256 requires at least a 256 bit (32 byte) key. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration}") long expirationMillis) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // Fail fast at startup rather than issuing tokens nobody can trust.
            throw new IllegalStateException(
                    "app.jwt.secret must be at least %d bytes long (was %d); set the JWT_SECRET environment variable"
                            .formatted(MIN_SECRET_BYTES, keyBytes.length));
        }
        if (expirationMillis <= 0) {
            throw new IllegalStateException("app.jwt.expiration must be positive (was %d)".formatted(expirationMillis));
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMillis = expirationMillis;
    }

    /** Signs a token for the given user. */
    public String generateToken(Long userId, String email, String role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and verifies a token.
     *
     * @return the principal, or {@link Optional#empty()} when the token is missing, malformed,
     * tampered with, expired or otherwise unusable
     */
    public Optional<AuthenticatedUser> parseToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            if (email == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(userId, email, role));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Token lifetime in seconds, as reported to clients in {@code expiresIn}. */
    public long getExpirationSeconds() {
        return expirationMillis / 1000;
    }
}

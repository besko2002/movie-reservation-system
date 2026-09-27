package com.example.moviereservation.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for token issuing and verification; no Spring context needed. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-unit-test-jwt-secret-0123456789";
    private static final String OTHER_SECRET = "another-unit-test-secret-another-unit-test-secret-9876";
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    private final JwtService jwtService = new JwtService(SECRET, ONE_HOUR_MILLIS);

    @Test
    void generatedTokenRoundTripsBackToThePrincipal() {
        String token = jwtService.generateToken(42L, "alice@example.com", "USER");

        Optional<AuthenticatedUser> principal = jwtService.parseToken(token);

        assertThat(principal).isPresent();
        assertThat(principal.get().id()).isEqualTo(42L);
        assertThat(principal.get().email()).isEqualTo("alice@example.com");
        assertThat(principal.get().role()).isEqualTo("USER");
        assertThat(principal.get().authority()).isEqualTo("ROLE_USER");
    }

    @Test
    void tokenCarriesSubjectEmailRoleAndIssuedAtAndExpiry() {
        String token = jwtService.generateToken(7L, "admin@cinema.local", "ADMIN");

        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get("email", String.class)).isEqualTo("admin@cinema.local");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(jwtService.getExpirationSeconds()).isEqualTo(3600L);
    }

    @Test
    void tokenWithTamperedSignatureIsRejected() {
        String token = jwtService.generateToken(1L, "alice@example.com", "USER");
        String tampered = withTamperedSignature(token);

        assertThat(tampered).isNotEqualTo(token);
        assertThat(jwtService.parseToken(tampered)).isEmpty();
    }

    @Test
    void tokenWithTamperedPayloadIsRejected() {
        String token = jwtService.generateToken(1L, "alice@example.com", "USER");
        String[] parts = token.split("\\.");
        // Re-sign nothing: only the payload is swapped, so the original signature no longer matches.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"999\",\"email\":\"attacker@example.com\",\"role\":\"ADMIN\"}"
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwtService.parseToken(tampered)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreignToken = new JwtService(OTHER_SECRET, ONE_HOUR_MILLIS)
                .generateToken(1L, "alice@example.com", "ADMIN");

        assertThat(jwtService.parseToken(foreignToken)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtService shortLived = new JwtService(SECRET, 1L);
        String token = shortLived.generateToken(5L, "bob@example.com", "USER");

        Thread.sleep(50);

        assertThat(shortLived.parseToken(token)).isEmpty();
        assertThat(jwtService.parseToken(token)).isEmpty();
    }

    @Test
    void expiredTokenBuiltDirectlyIsRejected() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(7200);
        String expired = Jwts.builder()
                .subject("3")
                .claim("email", "carol@example.com")
                .claim("role", "USER")
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.parseToken(expired)).isEmpty();
    }

    @Test
    void garbageNullAndBlankTokensAreRejected() {
        assertThat(jwtService.parseToken(null)).isEmpty();
        assertThat(jwtService.parseToken("   ")).isEmpty();
        assertThat(jwtService.parseToken("not-a-jwt")).isEmpty();
        assertThat(jwtService.parseToken("aaa.bbb.ccc")).isEmpty();
    }

    @Test
    void secretShorterThan32BytesFailsFast() {
        assertThatThrownBy(() -> new JwtService("too-short-secret", ONE_HOUR_MILLIS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.secret");
    }

    @Test
    void nonPositiveExpirationFailsFast() {
        assertThatThrownBy(() -> new JwtService(SECRET, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.jwt.expiration");
    }

    /**
     * Replaces the first character of the signature segment. The last base64url character of an
     * HS256 signature carries padding bits that decode to the same bytes, so tampering there would
     * not change the verified signature at all.
     */
    private static String withTamperedSignature(String token) {
        String[] parts = token.split("\\.");
        String signature = parts[2];
        char first = signature.charAt(0);
        char replacement = first == 'a' ? 'b' : 'a';
        return parts[0] + "." + parts[1] + "." + replacement + signature.substring(1);
    }
}

package ipl.pdms.shared.security;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

/**
 * Utility class for generating JWT tokens in tests.
 * Uses a hardcoded test key pair that matches the test public key.
 * 
 * Usage in tests:
 * <pre>
 * String token = TestJwtTokenGenerator.generateToken("test-user");
 * headers.setBearerAuth(token);
 * </pre>
 */
@Slf4j
public class TestJwtTokenGenerator {

    // Static key pair - generated once and reused
    private static final KeyPair TEST_KEY_PAIR;
    private static final PrivateKey TEST_PRIVATE_KEY;

    static {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            TEST_KEY_PAIR = keyGen.generateKeyPair();
            TEST_PRIVATE_KEY = TEST_KEY_PAIR.getPrivate();
            log.debug("Test JWT key pair generated");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test key pair", e);
        }
    }

    /**
     * Gets the test public key (for validation in tests).
     */
    public static java.security.PublicKey getTestPublicKey() {
        return TEST_KEY_PAIR.getPublic();
    }

    /**
     * Generates a JWT token for testing.
     * 
     * @param subject the subject (user ID, username, etc.)
     * @return the JWT token
     */
    public static String generateToken(String subject) {
        return generateToken(subject, 24, null);
    }

    /**
     * Generates a JWT token with custom expiration.
     * 
     * @param subject the subject
     * @param expirationHours expiration time in hours
     * @return the JWT token
     */
    public static String generateToken(String subject, Integer expirationHours) {
        return generateToken(subject, expirationHours, null);
    }

    /**
     * Generates a JWT token with custom claims.
     * 
     * @param subject the subject
     * @param expirationHours expiration time in hours
     * @param claims custom claims to include
     * @return the JWT token
     */
    public static String generateToken(String subject, Integer expirationHours, Map<String, Object> claims) {
        int hours = expirationHours != null ? expirationHours : 24;
        Instant now = Instant.now();
        Instant expiration = now.plus(hours, ChronoUnit.HOURS);

        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration));

        if (claims != null) {
            claims.forEach(builder::claim);
        }

        return builder.signWith(TEST_PRIVATE_KEY)
                .compact();
    }

    /**
     * Generates an expired token (for testing expiration validation).
     * 
     * @param subject the subject
     * @return an expired JWT token
     */
    public static String generateExpiredToken(String subject) {
        Instant now = Instant.now();
        Instant expiration = now.minus(1, ChronoUnit.HOURS); // Expired 1 hour ago

        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now.minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(expiration))
                .signWith(TEST_PRIVATE_KEY)
                .compact();
    }
}



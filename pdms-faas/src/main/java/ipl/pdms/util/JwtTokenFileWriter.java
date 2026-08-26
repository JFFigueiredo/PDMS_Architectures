package ipl.pdms.util;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Generates a JWT token on application startup and prints it to the console/logs
 * for use in JMeter tests and other external tools.
 */
@Slf4j
@Component
public class JwtTokenFileWriter implements CommandLineRunner {

    private static final String PRIVATE_KEY_PATH = "jwt-keys/private-key.pem";

    @Override
    public void run(String... args) {
        log.info("=== JwtTokenFileWriter: Starting token generation ===");
        try {
            log.info("Generating JWT token for testing...");
            String token = generateToken();
            printToken(token);
            log.info("=== JwtTokenFileWriter: Token generation completed successfully ===");
        } catch (Exception e) {
            log.error("=== JwtTokenFileWriter: Failed to generate JWT token ===");
            log.error("Error: {}", e.getMessage(), e);
            log.warn("You can generate it manually using GenerateJwtToken utility.");
        }
    }

    private String generateToken() throws Exception {
        String privateKeyContent = loadPrivateKeyFromClasspath();
        if (privateKeyContent == null) {
            // Fallback to file system for backward compatibility
            Path privateKeyPath = findPrivateKeyFile();
            if (privateKeyPath == null || !Files.exists(privateKeyPath)) {
                throw new RuntimeException("Private key file not found. Expected at classpath:jwt-keys/private-key.pem or " + PRIVATE_KEY_PATH);
            }
            privateKeyContent = Files.readString(privateKeyPath);
        }
        privateKeyContent = privateKeyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        Date now = new Date();
        // Token valid for 1 year
        Date expiration = new Date(now.getTime() + (365L * 24 * 60 * 60 * 1000));

        return Jwts.builder()
                .subject("test-user")
                .issuedAt(now)
                .expiration(expiration)
                .claim("roles", List.of("USER", "ADMIN"))
                .signWith(privateKey)
                .compact();
    }

    private String loadPrivateKeyFromClasspath() {
        try {
            Resource resource = new ClassPathResource("jwt-keys/private-key.pem");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    byte[] bytes = is.readAllBytes();
                    String content = new String(bytes);
                    log.info("Loaded private key from classpath: jwt-keys/private-key.pem");
                    return content;
                }
            }
        } catch (Exception e) {
            log.debug("Could not load private key from classpath: {}", e.getMessage());
        }
        return null;
    }

    private Path findPrivateKeyFile() {
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        log.info("Working directory: {}", workingDir);
        
        Path[] pathsToTry = {
            // Container path (most likely)
            Paths.get("/app/jwt-keys/private-key.pem"),
            // Local development paths
            workingDir.resolve(PRIVATE_KEY_PATH).normalize(),
            workingDir.resolve("pdms-monolith").resolve(PRIVATE_KEY_PATH).normalize(),
            workingDir.resolve("pdms-monolith").resolve("jwt-keys").resolve("private-key.pem").normalize(),
            Paths.get("/app/pdms-monolith/jwt-keys/private-key.pem"),
            // Also try absolute path from working dir
            Paths.get(PRIVATE_KEY_PATH)
        };

        for (Path tryPath : pathsToTry) {
            if (Files.exists(tryPath) && Files.isRegularFile(tryPath)) {
                log.info("Found private key at: {}", tryPath);
                return tryPath;
            }
        }
        log.error("Private key not found. Tried paths:");
        for (Path tryPath : pathsToTry) {
            log.error("  - {} (exists: {})", tryPath, Files.exists(tryPath));
        }
        return null;
    }

    private void printToken(String token) {
        // Use both System.out and logger to ensure visibility in containers
        String separator = "=".repeat(80);
        String message = "\n" + separator + "\n" +
                "JWT TOKEN FOR TESTING\n" +
                separator + "\n" +
                "Token (valid for 1 year):\n" +
                token + "\n" +
                "\nUse this token in the Authorization header:\n" +
                "Bearer " + token + "\n" +
                "\nFor JMeter, set JWT_TOKEN variable to:\n" +
                token + "\n" +
                separator + "\n";
        
        System.out.println(message);
        System.out.flush(); // Ensure it's flushed immediately
        
        log.info("JWT token generated successfully");
        log.info("Token: {}", token);
    }
}


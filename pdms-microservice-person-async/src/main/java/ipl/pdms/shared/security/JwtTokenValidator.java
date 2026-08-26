package ipl.pdms.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * Validates JWT tokens using RSA public key.
 * The public key is loaded from a PEM file or resource.
 */
@Slf4j
@Component
public class JwtTokenValidator {

    private final PublicKey publicKey;

    public JwtTokenValidator(
            @Autowired(required = false) @Qualifier("testPublicKeyResource") Resource testPublicKeyResource,
            @Value("${jwt.public-key.path:classpath:jwt/public-key.pem}") Resource publicKeyResource) {
        Resource resourceToUse = testPublicKeyResource != null ? testPublicKeyResource : publicKeyResource;
        PublicKey key;
        try {
            key = loadPublicKey(resourceToUse);
            log.info("JWT public key loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load JWT public key: {}. JWT validation will fail.", e.getMessage());
            try {
                java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
                keyGen.initialize(2048);
                key = keyGen.generateKeyPair().getPublic();
                log.warn("Using temporary key - JWT validation will not work until proper key is provided");
            } catch (Exception ex) {
                throw new RuntimeException("Failed to initialize JWT validator", ex);
            }
        }
        this.publicKey = key;
    }

    /**
     * Validates a JWT token.
     * 
     * @param token the JWT token to validate
     * @return true if the token is valid, false otherwise
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            if (claims.getExpiration().before(new java.util.Date())) {
                log.warn("JWT token has expired");
                return false;
            }
            
            log.trace("JWT token validated successfully for subject: {}", claims.getSubject());
            return true;
        } catch (Exception e) {
            log.warn("JWT token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts claims from a JWT token.
     * 
     * @param token the JWT token
     * @return the claims, or null if token is invalid
     */
    public Claims extractClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("Failed to extract claims from JWT token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Loads a public key from a PEM file.
     */
    private PublicKey loadPublicKey(Resource resource) throws Exception {
        String keyContent = readKeyContent(resource);
        String cleanedKeyContent = removePemHeaders(keyContent);
        return parsePublicKey(cleanedKeyContent);
    }

    private String readKeyContent(Resource resource) throws IOException {
        if (resource.exists() && resource.isFile()) {
            String content = Files.readString(Paths.get(resource.getURI()));
            log.info("Loaded JWT public key from file: {}", resource.getURI());
            return content;
        } else if (resource.exists()) {
            String content = new String(resource.getInputStream().readAllBytes());
            log.info("Loaded JWT public key from resource: {}", resource);
            return content;
        } else {
            return readKeyContentFromFileSystem(resource);
        }
    }

    private String readKeyContentFromFileSystem(Resource resource) throws IOException {
        String resourcePath = extractResourcePath(resource);
        String path = normalizePath(resourcePath);
        String filename = extractFilename(path);
        java.nio.file.Path resolvedPath = findKeyFileInCommonLocations(path, filename);
        
        if (resolvedPath != null) {
            String content = Files.readString(resolvedPath);
            log.info("Loaded JWT public key from: {}", resolvedPath);
            return content;
        } else {
            logKeyFileSearchFailure(path, filename);
            throw new IOException("Public key file not found. Resource: " + resource + ", Path: " + path);
        }
    }

    private String extractResourcePath(Resource resource) {
        try {
            return resource.getURI().toString();
        } catch (Exception e) {
            String description = resource.getDescription();
            return description != null && !description.isEmpty() ? description : "./jwt-keys/public-key.pem";
        }
    }

    private String normalizePath(String resourcePath) {
        String path = resourcePath.replace("file:", "").replace("file://", "");
        return path.startsWith("./") ? path.substring(2) : path;
    }

    private String extractFilename(String path) {
        if (path.contains("/")) {
            return path.substring(path.lastIndexOf("/") + 1);
        } else if (path.contains("\\")) {
            return path.substring(path.lastIndexOf("\\") + 1);
        }
        return path;
    }

    private java.nio.file.Path findKeyFileInCommonLocations(String path, String filename) {
        java.nio.file.Path workingDir = Paths.get(System.getProperty("user.dir"));
        java.nio.file.Path[] pathsToTry = {
            workingDir.resolve(path).normalize(),
            workingDir.resolve("jwt-keys").resolve(filename).normalize(),
            workingDir.resolve("pdms-monolith").resolve(path).normalize(),
            workingDir.resolve("pdms-monolith").resolve("jwt-keys").resolve(filename).normalize(),
            workingDir.resolve("pdms-monolith").resolve("jwt-keys").resolve("public-key.pem").normalize()
        };
        
        for (java.nio.file.Path tryPath : pathsToTry) {
            if (Files.exists(tryPath) && Files.isRegularFile(tryPath)) {
                return tryPath;
            }
        }
        return null;
    }

    private void logKeyFileSearchFailure(String path, String filename) {
        java.nio.file.Path workingDir = Paths.get(System.getProperty("user.dir"));
        java.nio.file.Path[] pathsToTry = {
            workingDir.resolve(path).normalize(),
            workingDir.resolve("jwt-keys").resolve(filename).normalize(),
            workingDir.resolve("pdms-monolith").resolve(path).normalize(),
            workingDir.resolve("pdms-monolith").resolve("jwt-keys").resolve(filename).normalize(),
            workingDir.resolve("pdms-monolith").resolve("jwt-keys").resolve("public-key.pem").normalize()
        };
        log.error("JWT public key file not found. Tried paths:");
        for (java.nio.file.Path tryPath : pathsToTry) {
            log.error("  - {}", tryPath);
        }
    }

    private String removePemHeaders(String keyContent) {
        return keyContent.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private PublicKey parsePublicKey(String cleanedKeyContent) throws Exception {
        byte[] keyBytes = java.util.Base64.getDecoder().decode(cleanedKeyContent);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }
}



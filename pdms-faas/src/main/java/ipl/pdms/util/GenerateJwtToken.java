package ipl.pdms.util;

import io.jsonwebtoken.Jwts;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Utility to generate JWT tokens for testing.
 * Run this class to generate a token that can be used with the API.
 */
public class GenerateJwtToken {
    
    public static void main(String[] args) throws Exception {
        String privateKeyPath = "jwt-keys/private-key.pem";
        String privateKeyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)));
        
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
        Date expiration = new Date(now.getTime() + 3600000);
        
        String token = Jwts.builder()
                .subject("test-user")
                .issuedAt(now)
                .expiration(expiration)
                .claim("roles", List.of("USER", "ADMIN"))
                .signWith(privateKey)
                .compact();
        
        System.out.println("JWT Token:");
        System.out.println(token);
        System.out.println("\nUse this token in the Authorization header:");
        System.out.println("Bearer " + token);
    }
}

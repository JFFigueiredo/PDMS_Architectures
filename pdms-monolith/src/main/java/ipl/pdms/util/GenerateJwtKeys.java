package ipl.pdms.util;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import io.jsonwebtoken.Jwts;

/**
 * Utility to generate RSA key pair and JWT tokens for testing.
 * Run this class to generate keys and a token that can be used with the API.
 */
public class GenerateJwtKeys {
    
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("jwt-keys"));
        
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();
        
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded()) +
                "\n-----END PUBLIC KEY-----";
        
        Files.write(Paths.get("jwt-keys/public-key.pem"), publicKeyPem.getBytes());
        System.out.println("✓ Public key saved to jwt-keys/public-key.pem");
        
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded()) +
                "\n-----END PRIVATE KEY-----";
        
        Files.write(Paths.get("jwt-keys/private-key.pem"), privateKeyPem.getBytes());
        System.out.println("✓ Private key saved to jwt-keys/private-key.pem");
        
        Date now = new Date();
        Date expiration = new Date(now.getTime() + (10L * 365 * 24 * 60 * 60 * 1000));
        
        String token = Jwts.builder()
                .subject("test-user")
                .issuedAt(now)
                .expiration(expiration)
                .claim("roles", List.of("USER", "ADMIN"))
                .signWith(privateKey)
                .compact();
        
        String tokenFile = "jwt-keys/hardcoded-token.txt";
        Files.write(Paths.get(tokenFile), token.getBytes());
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("HARDCODED JWT TOKEN GENERATED");
        System.out.println("=".repeat(70));
        System.out.println("Token saved to: " + tokenFile);
        System.out.println("Expiration: " + expiration + " (10 years from now)");
        System.out.println("");
        System.out.println("Token:");
        System.out.println(token);
        System.out.println("");
        System.out.println("Use this token in the Authorization header:");
        System.out.println("Bearer " + token);
        System.out.println("=".repeat(70));
    }
}
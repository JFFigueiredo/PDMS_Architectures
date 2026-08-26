package ipl.pdms.shared.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Minimal utility for password hashing with salt.
 * Uses PBKDF2 (built into Java, no dependencies needed).
 */
public class PasswordUtil {
    
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    /**
     * Generates a random salt.
     * @return Base64-encoded salt
     */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
    
    /**
     * Hashes a password with the given salt.
     * @param password plain text password
     * @param salt Base64-encoded salt
     * @return Base64-encoded hash
     */
    public static String hashPassword(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Password hashing failed", e);
        }
    }
    
    /**
     * Verifies a password against stored hash and salt.
     * @param password plain text password to verify
     * @param hash Base64-encoded stored hash
     * @param salt Base64-encoded stored salt
     * @return true if password matches
     */
    public static boolean verifyPassword(String password, String hash, String salt) {
        String computedHash = hashPassword(password, salt);
        return computedHash.equals(hash);
    }
}



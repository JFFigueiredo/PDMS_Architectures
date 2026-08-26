package ipl.pdms.shared.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Service for password hashing and verification using BCrypt.
 * Implements secure password hashing with salt as per feature requirements.
 */
@Service
public class PasswordHashingService {
    
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;
    
    public PasswordHashingService() {
        // BCrypt strength 8: fast (~20-50ms) while still secure
        // Strength 12 was taking ~500ms, strength 10 ~100ms - both too slow for API responses
        // Strength 8 provides good security with acceptable performance for real-time API calls
        this.passwordEncoder = new BCryptPasswordEncoder(8);
        this.secureRandom = new SecureRandom();
    }
    
    /**
     * Hashes a password using BCrypt.
     * BCrypt automatically generates and includes a salt in the hash.
     * 
     * @param plainPassword the plain text password
     * @return the hashed password (includes salt)
     */
    public String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        return passwordEncoder.encode(plainPassword);
    }
    
    /**
     * Verifies a plain password against a hashed password.
     * 
     * @param plainPassword the plain text password to verify
     * @param hashedPassword the hashed password to compare against
     * @return true if the password matches, false otherwise
     */
    public boolean verifyPassword(String plainPassword, String hashedPassword) {
        if (plainPassword == null || hashedPassword == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, hashedPassword);
    }
    
    /**
     * Generates a secure random salt.
     * Note: BCrypt includes salt in the hash, but this method can be used
     * for other purposes if needed.
     * 
     * @param length the length of the salt in bytes
     * @return the salt as a hexadecimal string
     */
    public String generateSalt(int length) {
        byte[] saltBytes = new byte[length];
        secureRandom.nextBytes(saltBytes);
        StringBuilder hexString = new StringBuilder();
        for (byte b : saltBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    /**
     * Generates a 16-byte salt (32 hex characters).
     * 
     * @return the salt as a hexadecimal string
     */
    public String generateSalt() {
        return generateSalt(16);
    }
}



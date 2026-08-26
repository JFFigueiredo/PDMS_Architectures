package ipl.pdms.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ipl.pdms.domain.Consent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical JSON + SHA-256 for consent payloads (shared by PG hash chain and Besu anchor hash).
 */
@Component
public class ConsentPayloadHasher {

    private final ObjectMapper canonicalObjectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public Map<String, Object> buildPayloadMap(String anchorType, UUID personId, Consent consent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("anchorType", anchorType);
        payload.put("personId", personId.toString());
        payload.put("consentId", consent.id().toString());
        payload.put("consentType", consent.consentType());
        payload.put("granted", consent.granted());
        payload.put("grantedAt", consent.grantedAt());
        payload.put("expiresAt", consent.expiresAt());
        payload.put("consentMethod", consent.consentMethod());
        payload.put("consentVersion", consent.consentVersion());
        payload.put("isActive", consent.isActive());
        payload.put("withdrawalReason", consent.withdrawalReason());
        payload.put("withdrawnAt", consent.withdrawnAt());
        payload.put("version", consent.version());
        payload.put("updatedAt", consent.updatedAt());
        return payload;
    }

    public String payloadHash(String anchorType, UUID personId, Consent consent) {
        return sha256Hex(asCanonicalJson(buildPayloadMap(anchorType, personId, consent)));
    }

    private String asCanonicalJson(Map<String, Object> payload) {
        try {
            return canonicalObjectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize consent payload", e);
        }
    }

    /** SHA-256 of UTF-8 string, lowercase hex (used for PG chain links). */
    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

}

package ipl.pdms.person;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Common ContactIdentity domain model used across all schema mappers.
 * Represents contact information: email, phone, or OIDC.
 */
@Builder
public record ContactIdentity(
        UUID id,
        UUID personId,
        ContactType type,
        String value,
        Instant verificationDate,
        Boolean primary,
        Instant createdAt,
        Instant updatedAt
) {
    public enum ContactType {
        PHONE,
        EMAIL,
        OIDC
    }
}



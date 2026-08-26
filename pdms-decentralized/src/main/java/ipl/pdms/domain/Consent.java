package ipl.pdms.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Common Consent domain model used across all schema mappers.
 * This represents the complete consent data structure.
 */
@Builder
public record Consent(
        UUID id,
        UUID personId,
        String consentType,
        Boolean granted,
        Instant grantedAt,
        Instant expiresAt,
        String consentMethod,
        String consentVersion,
        Boolean isActive,
        String withdrawalReason,
        Instant withdrawnAt,
        Instant createdAt,
        Instant updatedAt,
        Integer version
) {
}
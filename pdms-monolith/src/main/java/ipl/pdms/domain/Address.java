package ipl.pdms.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Common Address domain model used across all schema mappers.
 */
@Builder
public record Address(
        UUID id,
        UUID personId,
        String addressType,
        String country,
        String postalCode,
        String city,
        String street,
        String streetNumber,
        String flat,
        String companyName,
        String additionalInformation,
        Boolean verifiedAddress,
        Boolean isPrimary,
        Instant createdAt,
        Instant updatedAt
) {
}


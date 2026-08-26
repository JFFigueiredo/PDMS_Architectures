package ipl.pdms.person;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Common UserAccount domain model used across all schema mappers.
 */
@Builder
public record UserAccount(
        UUID id,
        UUID personId,
        String status,
        String type,
        String passwordHash,
        String passwordSalt,
        Integer version,
        String firstName,
        String lastName,
        String surname,
        String taxNumber,
        Instant dateOfBirth,
        String gender,
        String nationality,
        Instant createdAt,
        Instant updatedAt
) {
}



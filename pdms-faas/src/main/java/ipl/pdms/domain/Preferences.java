package ipl.pdms.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Common Preferences domain model used across all schema mappers.
 */
@Builder
public record Preferences(
        UUID id,
        UUID personId,
        String language,
        String nickname,
        Instant createdAt,
        Instant updatedAt
) {
}


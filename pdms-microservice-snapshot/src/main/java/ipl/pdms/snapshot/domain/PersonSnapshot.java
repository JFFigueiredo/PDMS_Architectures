package ipl.pdms.snapshot.domain;

import ipl.pdms.person.PersonData;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a snapshot of a person's complete data at a specific point in time.
 * A snapshot captures the entire state of a person including all related entities.
 */
@Builder
public record PersonSnapshot(
        UUID snapshotId,
        UUID personId,
        Instant snapshotTimestamp,
        PersonData personData
) {
    /**
     * Creates a new snapshot with the current timestamp.
     */
    public static PersonSnapshot create(UUID personId, PersonData personData) {
        return PersonSnapshot.builder()
                .snapshotId(UUID.randomUUID())
                .personId(personId)
                .snapshotTimestamp(Instant.now())
                .personData(personData)
                .build();
    }
}



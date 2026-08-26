package ipl.pdms.person;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Common Person domain model used across all schema mappers.
 * This represents the complete person data structure.
 */
@Builder
public record PersonData(
        @JsonAlias("personId") UUID id,
        String status,
        Integer version,
        Instant createdAt,
        Instant updatedAt,
        List<UserAccount> userAccounts,
        @JsonAlias("contactIdentities") List<ContactIdentity> identities,
        List<Address> addresses,
        Preferences preferences
) {
}



package ipl.pdms.db.mapper;

import ipl.pdms.domain.ContactIdentity;
import java.util.List;
import java.util.UUID;

public record PersonRecord(
    Person person,
    List<UserAccountRecord> userAccounts,
    List<ContactIdentityRecord> contactIdentities,
    List<AddressRecord> addresses,
    List<ConsentRecord> consents,
    PreferencesRecord preferences
) {
    public record Person(UUID id, String status, Integer version, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record UserAccountRecord(UUID id, UUID personId, String status, String type, String passwordHash, String passwordSalt, Integer version, String firstName, String lastName, String surname, String taxNumber, java.time.Instant dateOfBirth, String gender, String nationality, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record ContactIdentityRecord(UUID id, UUID personId, ContactIdentity.ContactType type, String value, java.time.Instant verificationDate, Boolean primary, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record AddressRecord(UUID id, UUID personId, String addressType, String country, String postalCode, String city, String street, String streetNumber, String flat, String companyName, String additionalInformation, Boolean verifiedAddress, Boolean isPrimary, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record ConsentRecord(UUID id, UUID personId, String consentType, Boolean granted, java.time.Instant grantedAt, java.time.Instant expiresAt, String consentMethod, String consentVersion, Boolean isActive, String withdrawalReason, java.time.Instant withdrawnAt, java.time.Instant createdAt, java.time.Instant updatedAt, Integer version) {}
    public record PreferencesRecord(UUID id, UUID personId, String language, String nickname, java.time.Instant createdAt, java.time.Instant updatedAt) {}
}

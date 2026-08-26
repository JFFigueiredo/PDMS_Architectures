package ipl.pdms.controller;

import ipl.pdms.domain.*;
import ipl.pdms.controller.dto.CreatePersonRequestDto;
import ipl.pdms.service.PasswordHashingService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Converter utility to convert DTO to Person domain model.
 * 
 * This converter transforms the API DTO (CreatePersonRequestDto) into
 * the Person domain object (PersonData) that will be used by all schema mappers.
 */
@Component
public class PersonDataConverter {

    private final PasswordHashingService passwordHashingService;

    public PersonDataConverter(PasswordHashingService passwordHashingService) {
        this.passwordHashingService = passwordHashingService;
    }

    /**
     * Converts DTO to Person domain object.
     * 
     * This is the first conversion step: DTO → Person Java object
     *
     * @param dto the DTO from the API request
     * @return PersonData domain object (Person Java object)
     */
    public PersonData toPersonData(CreatePersonRequestDto dto) {
        return toPersonData(dto, null);
    }

    /**
     * Converts DTO to Person domain object with optional personId.
     * 
     * This is the first conversion step: DTO → Person Java object
     *
     * @param dto the DTO from the API request
     * @param personId optional personId (for updates). If null, generates a new UUID (for creates)
     * @return PersonData domain object (Person Java object)
     */
    public PersonData toPersonData(CreatePersonRequestDto dto, UUID personId) {
        UUID finalPersonId = personId != null ? personId : UUID.randomUUID();
        Instant now = Instant.now();

        return PersonData.builder()
                .id(finalPersonId)
                .status(dto.getStatus() != null ? dto.getStatus() : "ACTIVE")
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .userAccounts(convertUserAccounts(dto, finalPersonId, now))
                .identities(convertContactIdentities(dto, finalPersonId, now))
                .addresses(convertAddresses(dto, finalPersonId, now))
                .consents(convertConsents(dto, finalPersonId, now))
                .preferences(convertPreferences(dto, finalPersonId, now))
                .build();
    }

    private List<UserAccount> convertUserAccounts(CreatePersonRequestDto dto, UUID personId, Instant now) {
        if (dto.getUserAccount() == null) return List.of();
        var ua = dto.getUserAccount();
        String passwordHash = null;
        String passwordSalt = null;
        if (ua.getPassword() != null) {
            passwordHash = passwordHashingService.hashPassword(ua.getPassword());
            passwordSalt = passwordHashingService.generateSalt();
        }
        return List.of(UserAccount.builder()
                .id(UUID.randomUUID())
                .personId(personId)
                .status("ACTIVE")
                .type(ua.getType())
                .passwordHash(passwordHash)
                .passwordSalt(passwordSalt)
                .version(1)
                .firstName(ua.getFirstName())
                .lastName(ua.getLastName())
                .surname(ua.getSurname())
                .taxNumber(ua.getTaxNumber())
                .dateOfBirth(ua.getDateOfBirth())
                .gender(ua.getGender())
                .nationality(ua.getNationality())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private List<ContactIdentity> convertContactIdentities(CreatePersonRequestDto dto, UUID personId, Instant now) {
        if (dto.getContactIdentities() == null) return List.of();
        return dto.getContactIdentities().stream()
                .map(ci -> ContactIdentity.builder()
                        .id(UUID.randomUUID())
                        .personId(personId)
                        .type(parseContactType(ci.getType()))
                        .value(ci.getValue())
                        .verificationDate(ci.getVerificationDate() != null ? ci.getVerificationDate() : now)
                        .primary(ci.getPrimary() != null ? ci.getPrimary() : false)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .collect(Collectors.toList());
    }

    private List<Address> convertAddresses(CreatePersonRequestDto dto, UUID personId, Instant now) {
        if (dto.getAddresses() == null) return List.of();
        return dto.getAddresses().stream()
                .map(addr -> Address.builder()
                        .id(UUID.randomUUID())
                        .personId(personId)
                        .addressType(addr.getAddressType())
                        .country(addr.getCountry())
                        .postalCode(addr.getPostalCode())
                        .city(addr.getCity())
                        .street(addr.getStreet())
                        .streetNumber(addr.getStreetNumber())
                        .flat(addr.getFlat())
                        .companyName(addr.getCompanyName())
                        .additionalInformation(addr.getAdditionalInformation())
                        .verifiedAddress(addr.getVerifiedAddress() != null ? addr.getVerifiedAddress() : false)
                        .isPrimary(addr.getIsPrimary() != null ? addr.getIsPrimary() : false)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .collect(Collectors.toList());
    }

    private List<Consent> convertConsents(CreatePersonRequestDto dto, UUID personId, Instant now) {
        if (dto.getConsents() == null) return List.of();
        return dto.getConsents().stream()
                .map(c -> Consent.builder()
                        .id(UUID.randomUUID())
                        .personId(personId)
                        .consentType(c.getConsentType())
                        .granted(c.getGranted() != null ? c.getGranted() : true)
                        .grantedAt(c.getGrantedAt() != null ? c.getGrantedAt() : now)
                        .expiresAt(c.getExpiresAt())
                        .consentMethod(c.getConsentMethod())
                        .consentVersion(c.getConsentVersion())
                        .isActive(c.getIsActive() != null ? c.getIsActive() : true)
                        .withdrawalReason(c.getWithdrawalReason())
                        .withdrawnAt(c.getWithdrawnAt())
                        .createdAt(now)
                        .updatedAt(now)
                        .version(1)
                        .build())
                .collect(Collectors.toList());
    }

    private Preferences convertPreferences(CreatePersonRequestDto dto, UUID personId, Instant now) {
        if (dto.getPreferences() == null) return null;
        var p = dto.getPreferences();
        return Preferences.builder()
                .id(UUID.randomUUID())
                .personId(personId)
                .language(p.getLanguage())
                .nickname(p.getNickname())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ContactIdentity.ContactType parseContactType(String type) {
        if (type == null) {
            return ContactIdentity.ContactType.EMAIL;
        }
        try {
            return ContactIdentity.ContactType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ContactIdentity.ContactType.EMAIL;
        }
    }

}


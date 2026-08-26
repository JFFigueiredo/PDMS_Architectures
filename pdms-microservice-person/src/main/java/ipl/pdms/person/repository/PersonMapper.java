package ipl.pdms.person.repository;

import ipl.pdms.person.domain.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PersonMapper {
    
    public PersonRecord toDatabase(PersonData personData) {
        PersonRecord.Person person = new PersonRecord.Person(
            personData.id(), personData.status(), personData.version(),
            personData.createdAt(), personData.updatedAt()
        );
        
        List<PersonRecord.UserAccountRecord> userAccounts = personData.userAccounts() != null ?
            personData.userAccounts().stream().map(ua -> new PersonRecord.UserAccountRecord(
                ua.id(), ua.personId(), ua.status(), ua.type(), ua.passwordHash(), ua.passwordSalt(),
                ua.version(), ua.firstName(), ua.lastName(), ua.surname(), ua.taxNumber(),
                ua.dateOfBirth(), ua.gender(), ua.nationality(), ua.createdAt(), ua.updatedAt()
            )).toList() : List.of();
        
        List<PersonRecord.ContactIdentityRecord> contactIdentities = personData.identities() != null ?
            personData.identities().stream().map(ci -> new PersonRecord.ContactIdentityRecord(
                ci.id(), ci.personId(), ci.type(), ci.value(), ci.verificationDate(),
                ci.primary(), ci.createdAt(), ci.updatedAt()
            )).toList() : List.of();
        
        List<PersonRecord.AddressRecord> addresses = personData.addresses() != null ?
            personData.addresses().stream().map(addr -> new PersonRecord.AddressRecord(
                addr.id(), addr.personId(), addr.addressType(), addr.country(),
                addr.postalCode(), addr.city(), addr.street(), addr.streetNumber(),
                addr.flat(), addr.companyName(), addr.additionalInformation(),
                addr.verifiedAddress(), addr.isPrimary(), addr.createdAt(), addr.updatedAt()
            )).toList() : List.of();
        
        // Consents are managed by consent service - not included in person service
        List<PersonRecord.ConsentRecord> consents = List.of();
        
        PersonRecord.PreferencesRecord preferences = personData.preferences() != null ?
            new PersonRecord.PreferencesRecord(
                personData.preferences().id(), personData.preferences().personId(),
                personData.preferences().language(), personData.preferences().nickname(),
                personData.preferences().createdAt(), personData.preferences().updatedAt()
            ) : null;
        
        return new PersonRecord(person, userAccounts, contactIdentities, addresses, consents, preferences);
    }
    
    public PersonData toDomain(PersonRecord dbEntity) {
        if (dbEntity == null || dbEntity.person() == null) {
            return null;
        }
        
        UUID personId = dbEntity.person().id();
        
        List<UserAccount> userAccounts = new ArrayList<>();
        if (dbEntity.userAccounts() != null) {
            for (PersonRecord.UserAccountRecord ua : dbEntity.userAccounts()) {
                userAccounts.add(UserAccount.builder()
                        .id(ua.id())
                        .personId(ua.personId())
                        .status(ua.status())
                        .type(ua.type())
                        .passwordHash(ua.passwordHash())
                        .passwordSalt(ua.passwordSalt())
                        .version(ua.version())
                        .firstName(ua.firstName())
                        .lastName(ua.lastName())
                        .surname(ua.surname())
                        .taxNumber(ua.taxNumber())
                        .dateOfBirth(ua.dateOfBirth())
                        .gender(ua.gender())
                        .nationality(ua.nationality())
                        .createdAt(ua.createdAt())
                        .updatedAt(ua.updatedAt())
                        .build());
            }
        }
        
        List<ContactIdentity> identities = new ArrayList<>();
        if (dbEntity.contactIdentities() != null) {
            for (PersonRecord.ContactIdentityRecord ci : dbEntity.contactIdentities()) {
                identities.add(ContactIdentity.builder()
                        .id(ci.id())
                        .personId(ci.personId())
                        .type(ci.type())
                        .value(ci.value())
                        .verificationDate(ci.verificationDate())
                        .primary(ci.primary())
                        .createdAt(ci.createdAt())
                        .updatedAt(ci.updatedAt())
                        .build());
            }
        }
        
        List<Address> addresses = new ArrayList<>();
        if (dbEntity.addresses() != null) {
            for (PersonRecord.AddressRecord addr : dbEntity.addresses()) {
                addresses.add(Address.builder()
                        .id(addr.id())
                        .personId(addr.personId())
                        .addressType(addr.addressType())
                        .country(addr.country())
                        .postalCode(addr.postalCode())
                        .city(addr.city())
                        .street(addr.street())
                        .streetNumber(addr.streetNumber())
                        .flat(addr.flat())
                        .companyName(addr.companyName())
                        .additionalInformation(addr.additionalInformation())
                        .verifiedAddress(addr.verifiedAddress())
                        .isPrimary(addr.isPrimary())
                        .createdAt(addr.createdAt())
                        .updatedAt(addr.updatedAt())
                        .build());
            }
        }
        
        // Consents are managed by consent service - not included in person service
        Preferences preferences = null;
        if (dbEntity.preferences() != null) {
            var pref = dbEntity.preferences();
            preferences = Preferences.builder()
                    .id(pref.id())
                    .personId(pref.personId())
                    .language(pref.language())
                    .nickname(pref.nickname())
                    .createdAt(pref.createdAt())
                    .updatedAt(pref.updatedAt())
                    .build();
        }
        
        return PersonData.builder()
                .id(personId)
                .status(dbEntity.person().status())
                .version(dbEntity.person().version())
                .createdAt(dbEntity.person().createdAt())
                .updatedAt(dbEntity.person().updatedAt())
                .userAccounts(userAccounts)
                .identities(identities)
                .addresses(addresses)
                .preferences(preferences)
                .build();
    }
    
}

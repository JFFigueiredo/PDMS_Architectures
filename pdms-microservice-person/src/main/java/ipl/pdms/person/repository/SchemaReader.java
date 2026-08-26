package ipl.pdms.person.repository;

import ipl.pdms.person.repository.PersonMapper;
import ipl.pdms.person.repository.PersonRecord;
import ipl.pdms.person.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SchemaReader {
    private final JdbcTemplate jdbcTemplate;
    private final PersonMapper mapper;

    public PersonData readPersonById(UUID personId) {
        Map<String, Object> personRow = readPersonRow(personId);
        if (personRow == null) {
            return null;
        }
        
        PersonRecord.Person person = mapPersonRow(personRow);
        List<PersonRecord.UserAccountRecord> userAccounts = readAndMapUserAccounts(personId);
        List<PersonRecord.ContactIdentityRecord> contactIdentities = readAndMapContactIdentities(personId);
        List<PersonRecord.AddressRecord> addresses = readAndMapAddresses(personId);
        PersonRecord.PreferencesRecord preferences = readAndMapPreferences(personId);
        
        PersonRecord record = new PersonRecord(person, userAccounts, contactIdentities, addresses, Collections.emptyList(), preferences);
        return mapper.toDomain(record);
    }

    private Map<String, Object> readPersonRow(UUID personId) {
        List<Map<String, Object>> personRows = jdbcTemplate.queryForList(
                "SELECT * FROM person WHERE id = ?", personId);
        return personRows.isEmpty() ? null : personRows.get(0);
    }

    private PersonRecord.Person mapPersonRow(Map<String, Object> personRow) {
        return new PersonRecord.Person(
                getUUID(personRow, "id"),
                getString(personRow, "status"),
                getInteger(personRow, "version"),
                getInstant(personRow, "created_at"),
                getInstant(personRow, "updated_at")
        );
    }

    private List<PersonRecord.UserAccountRecord> readAndMapUserAccounts(UUID personId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM user_account WHERE person_id = ?", personId);
        List<PersonRecord.UserAccountRecord> userAccounts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            userAccounts.add(mapUserAccountRow(row));
        }
        return userAccounts;
    }

    private PersonRecord.UserAccountRecord mapUserAccountRow(Map<String, Object> row) {
        return new PersonRecord.UserAccountRecord(
                getUUID(row, "id"),
                getUUID(row, "person_id"),
                getString(row, "status"),
                getString(row, "type"),
                getString(row, "password_hash"),
                getString(row, "password_salt"),
                getInteger(row, "version"),
                getString(row, "first_name"),
                getString(row, "last_name"),
                getString(row, "surname"),
                getString(row, "tax_number"),
                getInstant(row, "date_of_birth"),
                getString(row, "gender"),
                getString(row, "nationality"),
                getInstant(row, "created_at"),
                getInstant(row, "updated_at")
        );
    }

    private List<PersonRecord.ContactIdentityRecord> readAndMapContactIdentities(UUID personId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM contact_identity WHERE person_id = ?", personId);
        List<PersonRecord.ContactIdentityRecord> contactIdentities = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            contactIdentities.add(mapContactIdentityRow(row));
        }
        return contactIdentities;
    }

    private PersonRecord.ContactIdentityRecord mapContactIdentityRow(Map<String, Object> row) {
        return new PersonRecord.ContactIdentityRecord(
                getUUID(row, "id"),
                getUUID(row, "person_id"),
                parseContactType(getString(row, "type")),
                getString(row, "value"),
                getInstant(row, "verification_date"),
                getBoolean(row, "is_primary"),
                getInstant(row, "created_at"),
                getInstant(row, "updated_at")
        );
    }

    private List<PersonRecord.AddressRecord> readAndMapAddresses(UUID personId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM address WHERE person_id = ?", personId);
        List<PersonRecord.AddressRecord> addresses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            addresses.add(mapAddressRow(row));
        }
        return addresses;
    }

    private PersonRecord.AddressRecord mapAddressRow(Map<String, Object> row) {
        return new PersonRecord.AddressRecord(
                getUUID(row, "id"),
                getUUID(row, "person_id"),
                getString(row, "address_type"),
                getString(row, "country"),
                getString(row, "postal_code"),
                getString(row, "city"),
                getString(row, "street"),
                getString(row, "street_number"),
                getString(row, "flat"),
                getString(row, "company_name"),
                getString(row, "additional_information"),
                getBoolean(row, "verified_address"),
                getBoolean(row, "is_primary"),
                getInstant(row, "created_at"),
                getInstant(row, "updated_at")
        );
    }

    private PersonRecord.PreferencesRecord readAndMapPreferences(UUID personId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM preferences WHERE person_id = ?", personId);
        if (rows.isEmpty()) {
            return null;
        }
        return mapPreferencesRow(rows.get(0));
    }

    private PersonRecord.PreferencesRecord mapPreferencesRow(Map<String, Object> row) {
        return new PersonRecord.PreferencesRecord(
                getUUID(row, "id"),
                getUUID(row, "person_id"),
                getString(row, "language"),
                getString(row, "nickname"),
                getInstant(row, "created_at"),
                getInstant(row, "updated_at")
        );
    }
    
    private UUID getUUID(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof UUID) return (UUID) value;
        if (value instanceof String) return UUID.fromString((String) value);
        return null;
    }
    
    private String getString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }
    
    private Integer getInteger(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }
    
    private Boolean getBoolean(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }
    
    private Instant getInstant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        return null;
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

    public PersonData findByEmail(String email) {
        List<Map<String, Object>> contactRows = jdbcTemplate.queryForList(
                "SELECT person_id FROM contact_identity WHERE type = 'EMAIL' AND value = ?", email);
        
        if (contactRows.isEmpty()) {
            return null;
        }
        
        UUID personId = getUUID(contactRows.get(0), "person_id");
        return readPersonById(personId);
    }

    public PersonData findByPhone(String phone) {
        List<Map<String, Object>> contactRows = jdbcTemplate.queryForList(
                "SELECT person_id FROM contact_identity WHERE type = 'PHONE' AND value = ?", phone);
        
        if (contactRows.isEmpty()) {
            return null;
        }
        
        UUID personId = getUUID(contactRows.get(0), "person_id");
        return readPersonById(personId);
    }
}


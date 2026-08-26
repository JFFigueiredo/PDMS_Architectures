package ipl.pdms.db.mapper;

import ipl.pdms.db.DbUtils;
import ipl.pdms.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified repository for person operations (read and write).
 * Replaces the CQRS separation of SchemaWriter and SchemaReader.
 */
@Component
@RequiredArgsConstructor
public class PersonRepository {
    private final JdbcTemplate jdbcTemplate;
    private final PersonMapper mapper;

    // ==================== WRITE OPERATIONS ====================

    public void save(PersonData personData) {
        var record = mapper.toDatabase(personData);
        
        jdbcTemplate.update("INSERT INTO person (id, status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                record.person().id(), record.person().status(), record.person().version(),
                Timestamp.from(record.person().createdAt()), Timestamp.from(record.person().updatedAt()));

        insertRelatedEntities(record);
    }

    public void delete(UUID personId) {
        deleteRelatedEntities(personId);
        jdbcTemplate.update("DELETE FROM person WHERE id = ?", personId);
    }

    public void saveOrUpdate(PersonData personData) {
        var record = mapper.toDatabase(personData);
        
        var existingPersons = jdbcTemplate.queryForList(
                "SELECT id FROM person WHERE id = ?", UUID.class, record.person().id());
        
        if (existingPersons.isEmpty()) {
            jdbcTemplate.update("INSERT INTO person (id, status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    record.person().id(), record.person().status(), record.person().version(),
                    Timestamp.from(record.person().createdAt()), Timestamp.from(record.person().updatedAt()));
        } else {
            jdbcTemplate.update("UPDATE person SET status = ?, version = ?, updated_at = ? WHERE id = ?",
                    record.person().status(), record.person().version(),
                    Timestamp.from(record.person().updatedAt()), record.person().id());
        }

        deleteRelatedEntities(record.person().id());

        insertRelatedEntities(record);
    }

    private void deleteRelatedEntities(UUID personId) {
        jdbcTemplate.update("DELETE FROM preferences WHERE person_id = ?", personId);
        jdbcTemplate.update("DELETE FROM consent WHERE person_id = ?", personId);
        jdbcTemplate.update("DELETE FROM address WHERE person_id = ?", personId);
        jdbcTemplate.update("DELETE FROM contact_identity WHERE person_id = ?", personId);
        jdbcTemplate.update("DELETE FROM user_account WHERE person_id = ?", personId);
    }

    private void insertRelatedEntities(PersonRecord record) {
        insertUserAccounts(record.userAccounts());
        insertContactIdentities(record.contactIdentities());
        insertAddresses(record.addresses());
        insertConsents(record.consents());
        insertPreferences(record.preferences());
    }

    private void insertUserAccounts(List<PersonRecord.UserAccountRecord> userAccounts) {
        if (userAccounts == null || userAccounts.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
            INSERT INTO user_account (id, person_id, status, type, password_hash, password_salt,
                version, first_name, last_name, surname, tax_number, date_of_birth, gender,
                nationality, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(@NonNull java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    var ua = userAccounts.get(i);
                    ps.setObject(1, ua.id());
                    ps.setObject(2, ua.personId());
                    ps.setString(3, ua.status());
                    ps.setString(4, ua.type());
                    ps.setString(5, ua.passwordHash());
                    ps.setString(6, ua.passwordSalt());
                    ps.setInt(7, ua.version());
                    ps.setString(8, ua.firstName());
                    ps.setString(9, ua.lastName());
                    ps.setString(10, ua.surname());
                    ps.setString(11, ua.taxNumber());
                    ps.setTimestamp(12, toTimestamp(ua.dateOfBirth()));
                    ps.setString(13, ua.gender());
                    ps.setString(14, ua.nationality());
                    ps.setTimestamp(15, Timestamp.from(ua.createdAt()));
                    ps.setTimestamp(16, Timestamp.from(ua.updatedAt()));
                }
                @Override
                public int getBatchSize() {
                    return userAccounts.size();
                }
            });
    }

    private void insertContactIdentities(List<PersonRecord.ContactIdentityRecord> contactIdentities) {
        if (contactIdentities == null || contactIdentities.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
            INSERT INTO contact_identity (id, person_id, type, value, verification_date,
                is_primary, created_at, updated_at)
            VALUES (?, ?, ?::contact_identity_type, ?, ?, ?, ?, ?)
            ON CONFLICT (type, value) DO NOTHING
            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(@NonNull java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    var ci = contactIdentities.get(i);
                    ps.setObject(1, ci.id());
                    ps.setObject(2, ci.personId());
                    ps.setString(3, ci.type().name());
                    ps.setString(4, ci.value());
                    ps.setTimestamp(5, toTimestamp(ci.verificationDate()));
                    ps.setBoolean(6, ci.primary());
                    ps.setTimestamp(7, Timestamp.from(ci.createdAt()));
                    ps.setTimestamp(8, Timestamp.from(ci.updatedAt()));
                }
                @Override
                public int getBatchSize() {
                    return contactIdentities.size();
                }
            });
    }

    private void insertAddresses(List<PersonRecord.AddressRecord> addresses) {
        if (addresses == null || addresses.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
            INSERT INTO address (id, person_id, address_type, country, postal_code, city,
                street, street_number, flat, company_name, additional_information,
                verified_address, is_primary, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(@NonNull java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    var addr = addresses.get(i);
                    ps.setObject(1, addr.id());
                    ps.setObject(2, addr.personId());
                    ps.setString(3, addr.addressType());
                    ps.setString(4, addr.country());
                    ps.setString(5, addr.postalCode());
                    ps.setString(6, addr.city());
                    ps.setString(7, addr.street());
                    ps.setString(8, addr.streetNumber());
                    ps.setString(9, addr.flat());
                    ps.setString(10, addr.companyName());
                    ps.setString(11, addr.additionalInformation());
                    ps.setBoolean(12, addr.verifiedAddress());
                    ps.setBoolean(13, addr.isPrimary());
                    ps.setTimestamp(14, Timestamp.from(addr.createdAt()));
                    ps.setTimestamp(15, Timestamp.from(addr.updatedAt()));
                }
                @Override
                public int getBatchSize() {
                    return addresses.size();
                }
            });
    }

    private void insertConsents(List<PersonRecord.ConsentRecord> consents) {
        if (consents == null || consents.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
            INSERT INTO consent (id, person_id, consent_type, granted, granted_at, expires_at,
                consent_method, consent_version, is_active, withdrawal_reason, withdrawn_at,
                created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(@NonNull java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                    var c = consents.get(i);
                    ps.setObject(1, c.id());
                    ps.setObject(2, c.personId());
                    ps.setString(3, c.consentType());
                    ps.setBoolean(4, c.granted());
                    ps.setTimestamp(5, toTimestamp(c.grantedAt()));
                    ps.setTimestamp(6, toTimestamp(c.expiresAt()));
                    ps.setString(7, c.consentMethod());
                    ps.setString(8, c.consentVersion());
                    ps.setBoolean(9, c.isActive());
                    ps.setString(10, c.withdrawalReason());
                    ps.setTimestamp(11, toTimestamp(c.withdrawnAt()));
                    ps.setTimestamp(12, Timestamp.from(c.createdAt()));
                    ps.setTimestamp(13, Timestamp.from(c.updatedAt()));
                    ps.setInt(14, c.version());
                }
                @Override
                public int getBatchSize() {
                    return consents.size();
                }
            });
    }

    private void insertPreferences(PersonRecord.PreferencesRecord preferences) {
        if (preferences == null) return;
        jdbcTemplate.update("INSERT INTO preferences (id, person_id, language, nickname, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                preferences.id(), preferences.personId(), preferences.language(), preferences.nickname(),
                Timestamp.from(preferences.createdAt()), Timestamp.from(preferences.updatedAt()));
    }

    private Timestamp toTimestamp(Instant instant) {
        return DbUtils.toTimestamp(instant);
    }

    // ==================== READ OPERATIONS ====================

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


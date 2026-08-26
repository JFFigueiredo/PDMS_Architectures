package ipl.pdms.snapshot.repository;

import ipl.pdms.person.*;
import ipl.pdms.person.repository.PersonMapper;
import ipl.pdms.person.repository.PersonRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Snapshot writer for Normalized Schema.
 * Handles both write and read operations for person snapshots.
 */
@Component
@RequiredArgsConstructor
public class SchemaSnapshotWriter {
    private final JdbcTemplate jdbcTemplate;
    private final PersonMapper mapper;

    /**
     * Saves a snapshot to the database.
     * 
     * @param snapshot the snapshot to save
     */
    public void save(ipl.pdms.snapshot.domain.PersonSnapshot snapshot) {
        PersonData personData = snapshot.personData();
        var record = mapper.toDatabase(personData);
        
        try {
            int rowsAffected = jdbcTemplate.update("""
                INSERT INTO person_snapshot (snapshot_id, person_id, snapshot_timestamp, created_at,
                    person_status, person_version, person_created_at, person_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.snapshotId(), snapshot.personId(), 
                Timestamp.from(snapshot.snapshotTimestamp()), Timestamp.from(Instant.now()),
                record.person().status(), record.person().version(),
                toTimestamp(record.person().createdAt()), toTimestamp(record.person().updatedAt()));
            
            if (rowsAffected != 1) {
                throw new RuntimeException("Failed to insert person_snapshot: expected 1 row, got " + rowsAffected);
            }
            
            insertRelatedEntities(snapshot.snapshotId(), snapshot.personId(), record);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save snapshot " + snapshot.snapshotId() + " for person " + snapshot.personId(), e);
        }
    }

    /**
     * Reads a snapshot by its ID.
     * 
     * @param snapshotId the snapshot ID
     * @return the person snapshot, or null if not found
     */
    public ipl.pdms.snapshot.domain.PersonSnapshot read(UUID snapshotId) {
        Map<String, Object> personRow = readSnapshotPersonRow(snapshotId);
        if (personRow == null) {
            return null;
        }
        
        UUID personId = (UUID) personRow.get("person_id");
        PersonRecord.Person person = mapSnapshotPersonRow(personRow, personId);
        
        List<Map<String, Object>> userAccountRows = readSnapshotUserAccountRows(snapshotId);
        List<Map<String, Object>> contactRows = readSnapshotContactIdentityRows(snapshotId);
        List<Map<String, Object>> addressRows = readSnapshotAddressRows(snapshotId);
        List<Map<String, Object>> consentRows = readSnapshotConsentRows(snapshotId);
        List<Map<String, Object>> preferencesRows = readSnapshotPreferencesRows(snapshotId);
        
        PersonRecord normalizedRecord = buildPersonRecordFromSnapshot(
                person, userAccountRows, contactRows, addressRows, consentRows, preferencesRows);
        PersonData personData = mapper.toDomain(normalizedRecord);
        
        return buildPersonSnapshot(snapshotId, personId, personRow, personData);
    }

    /**
     * Reads the latest snapshot for a person.
     * 
     * @param personId the person ID
     * @return the latest person snapshot, or null if not found
     */
    public ipl.pdms.snapshot.domain.PersonSnapshot readLatest(UUID personId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT snapshot_id FROM person_snapshot 
            WHERE person_id = ? 
            ORDER BY snapshot_timestamp DESC 
            LIMIT 1
            """, personId);
        
        if (rows.isEmpty()) {
            return null;
        }
        
        UUID snapshotId = (UUID) rows.get(0).get("snapshot_id");
        return read(snapshotId);
    }

    /**
     * Finds a person ID by email using only snapshot data (person_snapshot_contact_identity).
     * Does not call the Person service.
     *
     * @param email the email to look up
     * @return the person ID, or null if no snapshot contains this email
     */
    public UUID findPersonIdByEmail(String email) {
        return findPersonIdByIdentity("EMAIL", email);
    }

    /**
     * Finds a person ID by phone using only snapshot data (person_snapshot_contact_identity).
     * Does not call the Person service.
     *
     * @param phone the phone to look up
     * @return the person ID, or null if no snapshot contains this phone
     */
    public UUID findPersonIdByPhone(String phone) {
        return findPersonIdByIdentity("PHONE", phone);
    }

    private UUID findPersonIdByIdentity(String type, String value) {
        if (value == null || value.isBlank()) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT person_id FROM person_snapshot_contact_identity
            WHERE type::text = ? AND value = ?
            LIMIT 1
            """, type, value);
        if (rows.isEmpty()) return null;
        Object val = rows.get(0).get("person_id");
        return val == null ? null : (val instanceof UUID ? (UUID) val : UUID.fromString(val.toString()));
    }

    private Map<String, Object> readSnapshotPersonRow(UUID snapshotId) {
        List<Map<String, Object>> personRows = jdbcTemplate.queryForList(
                "SELECT * FROM person_snapshot WHERE snapshot_id = ?", snapshotId);
        return personRows.isEmpty() ? null : personRows.get(0);
    }

    private PersonRecord.Person mapSnapshotPersonRow(Map<String, Object> personRow, UUID personId) {
        return new PersonRecord.Person(
                personId,
                getString(personRow, "person_status"),
                getInteger(personRow, "person_version"),
                getInstant(personRow, "person_created_at"),
                getInstant(personRow, "person_updated_at"));
    }

    private List<Map<String, Object>> readSnapshotUserAccountRows(UUID snapshotId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM person_snapshot_user_account WHERE snapshot_id = ?", snapshotId);
    }

    private List<Map<String, Object>> readSnapshotContactIdentityRows(UUID snapshotId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM person_snapshot_contact_identity WHERE snapshot_id = ?", snapshotId);
    }

    private List<Map<String, Object>> readSnapshotAddressRows(UUID snapshotId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM person_snapshot_address WHERE snapshot_id = ?", snapshotId);
    }

    private List<Map<String, Object>> readSnapshotConsentRows(UUID snapshotId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM person_snapshot_consent WHERE snapshot_id = ?", snapshotId);
    }

    private List<Map<String, Object>> readSnapshotPreferencesRows(UUID snapshotId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM person_snapshot_preferences WHERE snapshot_id = ?", snapshotId);
    }

    private PersonRecord buildPersonRecordFromSnapshot(
            PersonRecord.Person person,
            List<Map<String, Object>> userAccountRows,
            List<Map<String, Object>> contactRows,
            List<Map<String, Object>> addressRows,
            List<Map<String, Object>> consentRows,
            List<Map<String, Object>> preferencesRows) {
        return new PersonRecord(
                person,
                mapUserAccounts(userAccountRows),
                mapContactIdentities(contactRows),
                mapAddresses(addressRows),
                mapConsents(consentRows),
                mapPreferences(preferencesRows));
    }

    private ipl.pdms.snapshot.domain.PersonSnapshot buildPersonSnapshot(UUID snapshotId, UUID personId, Map<String, Object> personRow, PersonData personData) {
        return ipl.pdms.snapshot.domain.PersonSnapshot.builder()
                .snapshotId(snapshotId)
                .personId(personId)
                .snapshotTimestamp(((Timestamp) personRow.get("snapshot_timestamp")).toInstant())
                .personData(personData)
                .build();
    }

    private void insertRelatedEntities(UUID snapshotId, UUID personId, PersonRecord record) {
        insertSnapshotUserAccounts(snapshotId, personId, record.userAccounts());
        insertSnapshotContactIdentities(snapshotId, personId, record.contactIdentities());
        insertSnapshotAddresses(snapshotId, personId, record.addresses());
        insertSnapshotConsents(snapshotId, personId, record.consents());
        insertSnapshotPreferences(snapshotId, personId, record.preferences());
    }

    private void insertSnapshotUserAccounts(UUID snapshotId, UUID personId, List<PersonRecord.UserAccountRecord> userAccounts) {
        if (userAccounts == null) return;
        Instant now = Instant.now();
        for (var ua : userAccounts) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_user_account (
                    snapshot_id, person_id, id, status, type,
                    version, first_name, last_name, surname, tax_number, date_of_birth, gender,
                    nationality, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, personId, ua.id(), 
                    ua.status() != null ? ua.status() : "ACTIVE",
                    ua.type() != null ? ua.type() : "STANDARD",
                    ua.version() != null ? ua.version() : 1,
                    ua.firstName(), ua.lastName(), ua.surname(), ua.taxNumber(),
                    toTimestamp(ua.dateOfBirth()), ua.gender(), ua.nationality(),
                    toTimestamp(ua.createdAt() != null ? ua.createdAt() : now),
                    toTimestamp(ua.updatedAt() != null ? ua.updatedAt() : now));
        }
    }

    private void insertSnapshotContactIdentities(UUID snapshotId, UUID personId, List<PersonRecord.ContactIdentityRecord> contactIdentities) {
        if (contactIdentities == null) return;
        Instant now = Instant.now();
        for (var ci : contactIdentities) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_contact_identity (
                    snapshot_id, person_id, id, type, value, verification_date,
                    is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?::contact_identity_type, ?, ?, ?, ?, ?)
                """, snapshotId, personId, ci.id(), ci.type().name(), 
                    ci.value() != null ? ci.value() : "",
                    toTimestamp(ci.verificationDate()),
                    ci.primary() != null ? ci.primary() : false,
                    toTimestamp(ci.createdAt() != null ? ci.createdAt() : now),
                    toTimestamp(ci.updatedAt() != null ? ci.updatedAt() : now));
        }
    }

    private void insertSnapshotAddresses(UUID snapshotId, UUID personId, List<PersonRecord.AddressRecord> addresses) {
        if (addresses == null) return;
        Instant now = Instant.now();
        for (var addr : addresses) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_address (
                    snapshot_id, person_id, id, address_type, country, postal_code, city,
                    street, street_number, flat, company_name, additional_information,
                    verified_address, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, personId, addr.id(), addr.addressType(), addr.country(), addr.postalCode(),
                    addr.city(), addr.street(), addr.streetNumber(), addr.flat(), addr.companyName(),
                    addr.additionalInformation(),
                    addr.verifiedAddress() != null ? addr.verifiedAddress() : false,
                    addr.isPrimary() != null ? addr.isPrimary() : false,
                    toTimestamp(addr.createdAt() != null ? addr.createdAt() : now),
                    toTimestamp(addr.updatedAt() != null ? addr.updatedAt() : now));
        }
    }

    private void insertSnapshotConsents(UUID snapshotId, UUID personId, List<PersonRecord.ConsentRecord> consents) {
        if (consents == null) return;
        Instant now = Instant.now();
        for (var c : consents) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_consent (
                    snapshot_id, person_id, id, consent_type, granted, granted_at, expires_at,
                    consent_method, consent_version, is_active, withdrawal_reason, withdrawn_at,
                    created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, personId, c.id(),
                    c.consentType() != null ? c.consentType() : "UNKNOWN",
                    c.granted() != null ? c.granted() : false,
                    toTimestamp(c.grantedAt()), toTimestamp(c.expiresAt()),
                    c.consentMethod(), c.consentVersion(),
                    c.isActive() != null ? c.isActive() : true,
                    c.withdrawalReason(), toTimestamp(c.withdrawnAt()),
                    toTimestamp(c.createdAt() != null ? c.createdAt() : now),
                    toTimestamp(c.updatedAt() != null ? c.updatedAt() : now),
                    c.version() != null ? c.version() : 1);
        }
    }

    private void insertSnapshotPreferences(UUID snapshotId, UUID personId, PersonRecord.PreferencesRecord preferences) {
        if (preferences == null) return;
        Instant now = Instant.now();
        jdbcTemplate.update("""
            INSERT INTO person_snapshot_preferences (
                snapshot_id, person_id, id, language, nickname, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, snapshotId, personId, preferences.id(), preferences.language(), preferences.nickname(),
                toTimestamp(preferences.createdAt() != null ? preferences.createdAt() : now),
                toTimestamp(preferences.updatedAt() != null ? preferences.updatedAt() : now));
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    // Helper methods for reading
    private List<PersonRecord.UserAccountRecord> mapUserAccounts(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> new PersonRecord.UserAccountRecord(
                getUUID(row, "id"), getUUID(row, "person_id"), getString(row, "status"),
                getString(row, "type"), null, null, // password_hash and password_salt are not stored in snapshots for security
                getInteger(row, "version"), getString(row, "first_name"), getString(row, "last_name"),
                getString(row, "surname"), getString(row, "tax_number"), getInstant(row, "date_of_birth"),
                getString(row, "gender"), getString(row, "nationality"),
                getInstant(row, "created_at"), getInstant(row, "updated_at"))).toList();
    }

    private List<PersonRecord.ContactIdentityRecord> mapContactIdentities(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            String typeStr = getString(row, "type");
            ContactIdentity.ContactType type = ContactIdentity.ContactType.EMAIL;
            if (typeStr != null) {
                try {
                    type = ContactIdentity.ContactType.valueOf(typeStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                }
            }
            return new PersonRecord.ContactIdentityRecord(
                    getUUID(row, "id"), getUUID(row, "person_id"), type,
                    getString(row, "value"), getInstant(row, "verification_date"),
                    getBoolean(row, "is_primary"), getInstant(row, "created_at"), getInstant(row, "updated_at"));
        }).toList();
    }

    private List<PersonRecord.AddressRecord> mapAddresses(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> new PersonRecord.AddressRecord(
                getUUID(row, "id"), getUUID(row, "person_id"), getString(row, "address_type"),
                getString(row, "country"), getString(row, "postal_code"), getString(row, "city"),
                getString(row, "street"), getString(row, "street_number"), getString(row, "flat"),
                getString(row, "company_name"), getString(row, "additional_information"),
                getBoolean(row, "verified_address"), getBoolean(row, "is_primary"),
                getInstant(row, "created_at"), getInstant(row, "updated_at"))).toList();
    }

    private List<PersonRecord.ConsentRecord> mapConsents(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> new PersonRecord.ConsentRecord(
                getUUID(row, "id"), getUUID(row, "person_id"), getString(row, "consent_type"),
                getBoolean(row, "granted"), getInstant(row, "granted_at"), getInstant(row, "expires_at"),
                getString(row, "consent_method"), getString(row, "consent_version"), getBoolean(row, "is_active"),
                getString(row, "withdrawal_reason"), getInstant(row, "withdrawn_at"),
                getInstant(row, "created_at"), getInstant(row, "updated_at"), getInteger(row, "version"))).toList();
    }

    private PersonRecord.PreferencesRecord mapPreferences(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        return new PersonRecord.PreferencesRecord(
                getUUID(row, "id"), getUUID(row, "person_id"), getString(row, "language"),
                getString(row, "nickname"), getInstant(row, "created_at"), getInstant(row, "updated_at"));
    }

    private UUID getUUID(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID ? (UUID) value : value != null ? UUID.fromString(value.toString()) : null;
    }

    private String getString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getInteger(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
    }

    private Boolean getBoolean(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean ? (Boolean) value : value != null ? Boolean.parseBoolean(value.toString()) : null;
    }

    private Instant getInstant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        if (value instanceof Instant) return (Instant) value;
        return Instant.parse(value.toString());
    }
}


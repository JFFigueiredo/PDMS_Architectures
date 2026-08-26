package ipl.pdms.db.mapper;

import ipl.pdms.domain.ContactIdentity;
import ipl.pdms.domain.PersonData;
import ipl.pdms.domain.PersonSnapshot;
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
 * Stores snapshots in normalized tables following the normalized schema pattern.
 */
@Component
@RequiredArgsConstructor
public class SchemaSnapshotWriter {
    private final JdbcTemplate jdbcTemplate;
    private final PersonMapper mapper;

    public void save(PersonSnapshot snapshot) {
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
                Timestamp.from(record.person().createdAt()), Timestamp.from(record.person().updatedAt()));
            
            if (rowsAffected != 1) {
                throw new RuntimeException("Failed to insert person_snapshot: expected 1 row, got " + rowsAffected);
            }
            
            insertRelatedEntities(snapshot.snapshotId(), snapshot.personId(), record);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save snapshot " + snapshot.snapshotId() + " for person " + snapshot.personId(), e);
        }
    }

    public PersonSnapshot read(UUID snapshotId) {
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

    private PersonSnapshot buildPersonSnapshot(UUID snapshotId, UUID personId, Map<String, Object> personRow, PersonData personData) {
        return PersonSnapshot.builder()
                .snapshotId(snapshotId)
                .personId(personId)
                .snapshotTimestamp(((Timestamp) personRow.get("snapshot_timestamp")).toInstant())
                .personData(personData)
                .build();
    }

    public PersonSnapshot readLatest(UUID personId) {
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

    private void insertRelatedEntities(UUID snapshotId, UUID personId, PersonRecord record) {
        insertSnapshotUserAccounts(snapshotId, personId, record.userAccounts());
        insertSnapshotContactIdentities(snapshotId, personId, record.contactIdentities());
        insertSnapshotAddresses(snapshotId, personId, record.addresses());
        insertSnapshotConsents(snapshotId, personId, record.consents());
        insertSnapshotPreferences(snapshotId, personId, record.preferences());
    }

    private void insertSnapshotUserAccounts(UUID snapshotId, UUID personId, List<PersonRecord.UserAccountRecord> userAccounts) {
        if (userAccounts == null) return;
        for (var ua : userAccounts) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_user_account (
                    snapshot_id, person_id, id, status, type,
                    version, first_name, last_name, surname, tax_number, date_of_birth, gender,
                    nationality, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, personId, ua.id(), ua.status(), ua.type(),
                    ua.version(), ua.firstName(), ua.lastName(), ua.surname(), ua.taxNumber(),
                    toTimestamp(ua.dateOfBirth()), ua.gender(), ua.nationality(),
                    Timestamp.from(ua.createdAt()), Timestamp.from(ua.updatedAt()));
        }
    }

    private void insertSnapshotContactIdentities(UUID snapshotId, UUID personId, List<PersonRecord.ContactIdentityRecord> contactIdentities) {
        if (contactIdentities == null) return;
        for (var ci : contactIdentities) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_contact_identity (
                    snapshot_id, person_id, id, type, value, verification_date,
                    is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?::contact_identity_type, ?, ?, ?, ?, ?)
                """, snapshotId, personId, ci.id(), ci.type().name(), ci.value(), toTimestamp(ci.verificationDate()),
                    ci.primary(), Timestamp.from(ci.createdAt()), Timestamp.from(ci.updatedAt()));
        }
    }

    private void insertSnapshotAddresses(UUID snapshotId, UUID personId, List<PersonRecord.AddressRecord> addresses) {
        if (addresses == null) return;
        for (var addr : addresses) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_address (
                    snapshot_id, person_id, id, address_type, country, postal_code, city,
                    street, street_number, flat, company_name, additional_information,
                    verified_address, is_primary, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, personId, addr.id(), addr.addressType(), addr.country(), addr.postalCode(),
                    addr.city(), addr.street(), addr.streetNumber(), addr.flat(), addr.companyName(),
                    addr.additionalInformation(), addr.verifiedAddress(), addr.isPrimary(),
                    Timestamp.from(addr.createdAt()), Timestamp.from(addr.updatedAt()));
        }
    }

    private void insertSnapshotConsents(UUID snapshotId, UUID personId, List<PersonRecord.ConsentRecord> consents) {
        if (consents == null) return;
        for (var c : consents) {
            jdbcTemplate.update("""
                INSERT INTO person_snapshot_consent (
                    snapshot_id, person_id, id, consent_type, granted, granted_at, expires_at,
                    consent_method, consent_version, is_active, withdrawal_reason, withdrawn_at,
                    created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, personId, c.id(), c.consentType(), c.granted(), toTimestamp(c.grantedAt()),
                    toTimestamp(c.expiresAt()), c.consentMethod(), c.consentVersion(), c.isActive(),
                    c.withdrawalReason(), toTimestamp(c.withdrawnAt()), Timestamp.from(c.createdAt()),
                    Timestamp.from(c.updatedAt()), c.version());
        }
    }

    private void insertSnapshotPreferences(UUID snapshotId, UUID personId, PersonRecord.PreferencesRecord preferences) {
        if (preferences == null) return;
        jdbcTemplate.update("""
            INSERT INTO person_snapshot_preferences (
                snapshot_id, person_id, id, language, nickname, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, snapshotId, personId, preferences.id(), preferences.language(), preferences.nickname(),
                Timestamp.from(preferences.createdAt()), Timestamp.from(preferences.updatedAt()));
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
        return value instanceof Number ? ((Number) value).intValue() : value != null ? Integer.parseInt(value.toString()) : null;
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

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}


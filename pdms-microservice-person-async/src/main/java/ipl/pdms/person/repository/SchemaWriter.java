package ipl.pdms.person.repository;

import ipl.pdms.person.repository.PersonMapper;
import ipl.pdms.person.repository.PersonRecord;
import ipl.pdms.shared.db.DbUtils;
import ipl.pdms.person.domain.PersonData;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.lang.NonNull;

@Component
@RequiredArgsConstructor
public class SchemaWriter {
    private final JdbcTemplate jdbcTemplate;
    private final PersonMapper mapper;

    public void save(PersonData personData) {
        var record = mapper.toDatabase(personData);
        
        jdbcTemplate.update("INSERT INTO person (id, status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                record.person().id(), record.person().status(), record.person().version(),
                Timestamp.from(record.person().createdAt()), Timestamp.from(record.person().updatedAt()));

        insertRelatedEntities(record);
    }

    public void delete(java.util.UUID personId) {
        deleteRelatedEntities(personId);
        jdbcTemplate.update("DELETE FROM person WHERE id = ?", personId);
    }

    public void saveOrUpdate(PersonData personData) {
        var record = mapper.toDatabase(personData);
        
        var existingPersons = jdbcTemplate.queryForList(
                "SELECT id FROM person WHERE id = ?", java.util.UUID.class, record.person().id());
        
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

    private void deleteRelatedEntities(java.util.UUID personId) {
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
        // Consents are managed by consent service - not inserted here
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

    // Consents are managed by consent service - not inserted here

    private void insertPreferences(PersonRecord.PreferencesRecord preferences) {
        if (preferences == null) return;
        jdbcTemplate.update("INSERT INTO preferences (id, person_id, language, nickname, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                preferences.id(), preferences.personId(), preferences.language(), preferences.nickname(),
                Timestamp.from(preferences.createdAt()), Timestamp.from(preferences.updatedAt()));
    }

    private Timestamp toTimestamp(java.time.Instant instant) {
        return DbUtils.toTimestamp(instant);
    }
}


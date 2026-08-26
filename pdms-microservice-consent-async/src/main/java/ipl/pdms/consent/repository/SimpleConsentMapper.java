package ipl.pdms.consent.repository;

import ipl.pdms.consent.domain.Consent;
import ipl.pdms.shared.db.DbUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SimpleConsentMapper {
    
    private final JdbcTemplate jdbcTemplate;
    
    public Consent toDomain(Map<String, Object> row) {
        return Consent.builder()
            .id((UUID) row.get("id"))
            .personId((UUID) row.get("person_id"))
            .consentType((String) row.get("consent_type"))
            .granted((Boolean) row.get("granted"))
            .grantedAt(toInstant(row.get("granted_at")))
            .expiresAt(toInstant(row.get("expires_at")))
            .consentMethod((String) row.get("consent_method"))
            .consentVersion((String) row.get("consent_version"))
            .isActive((Boolean) row.get("is_active"))
            .withdrawalReason((String) row.get("withdrawal_reason"))
            .withdrawnAt(toInstant(row.get("withdrawn_at")))
            .createdAt(toInstant(row.get("created_at")))
            .updatedAt(toInstant(row.get("updated_at")))
            .version((Integer) row.get("version"))
            .build();
    }
    
    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        if (value instanceof String) {
            try {
                return Instant.parse((String) value);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
    
    public void save(Consent consent) {
        String sql = """
            INSERT INTO consent (id, person_id, consent_type, granted, granted_at, expires_at, 
                               consent_method, consent_version, is_active, withdrawal_reason, 
                               withdrawn_at, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
            consent.id(), consent.personId(), consent.consentType(), consent.granted(),
            DbUtils.toTimestamp(consent.grantedAt()), DbUtils.toTimestamp(consent.expiresAt()), 
            consent.consentMethod(), consent.consentVersion(), consent.isActive(), 
            consent.withdrawalReason(), DbUtils.toTimestamp(consent.withdrawnAt()), 
            DbUtils.toTimestamp(consent.createdAt()), DbUtils.toTimestamp(consent.updatedAt()), 
            consent.version());
    }
    
    public void update(Consent consent) {
        String sql = """
            UPDATE consent SET consent_type = ?, granted = ?, granted_at = ?, expires_at = ?,
                              consent_method = ?, consent_version = ?, is_active = ?,
                              withdrawal_reason = ?, withdrawn_at = ?, updated_at = ?, version = ?
            WHERE id = ?
            """;
        jdbcTemplate.update(sql,
            consent.consentType(), consent.granted(), 
            DbUtils.toTimestamp(consent.grantedAt()), DbUtils.toTimestamp(consent.expiresAt()),
            consent.consentMethod(), consent.consentVersion(), consent.isActive(),
            consent.withdrawalReason(), DbUtils.toTimestamp(consent.withdrawnAt()), 
            DbUtils.toTimestamp(consent.updatedAt()), consent.version(), consent.id());
    }
    
    public void delete(UUID consentId) {
        jdbcTemplate.update("DELETE FROM consent WHERE id = ?", consentId);
    }
    
    public Consent findById(UUID consentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM consent WHERE id = ?", consentId);
        return rows.isEmpty() ? null : toDomain(rows.get(0));
    }
    
    public List<Consent> findByPersonId(UUID personId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM consent WHERE person_id = ? ORDER BY created_at DESC", personId);
        return rows.stream().map(this::toDomain).collect(Collectors.toList());
    }
}


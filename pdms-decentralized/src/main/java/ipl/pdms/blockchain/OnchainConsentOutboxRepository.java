package ipl.pdms.blockchain;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pdms.blockchain.enabled", havingValue = "true")
public class OnchainConsentOutboxRepository {

    private final JdbcTemplate jdbc;

    public void insert(UUID id, UUID consentId, UUID personId, String anchorType, String consentJson) throws Exception {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(consentJson);
        jdbc.update(
                """
                INSERT INTO onchain_consent_outbox (id, consent_id, person_id, anchor_type, consent_snapshot, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """,
                id, consentId, personId, anchorType, json
        );
    }

    public List<PendingOnchainRow> findPending(int limit) {
        return jdbc.query(
                """
                SELECT id, consent_id, person_id, anchor_type, consent_snapshot::text AS consent_json
                FROM onchain_consent_outbox
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, row) -> new PendingOnchainRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("consent_id", UUID.class),
                        rs.getObject("person_id", UUID.class),
                        rs.getString("anchor_type"),
                        rs.getString("consent_json")
                ),
                limit
        );
    }

    public void markConfirmed(UUID id, String txHash, long blockNumber) {
        jdbc.update(
                """
                UPDATE onchain_consent_outbox
                SET status = 'CONFIRMED', tx_hash = ?, block_number = ?, error_message = NULL
                WHERE id = ?
                """,
                txHash, blockNumber, id
        );
    }

    public void bumpRetry(UUID id, String errorMessage) {
        jdbc.update(
                """
                UPDATE onchain_consent_outbox
                SET retry_count = retry_count + 1, error_message = ?
                WHERE id = ?
                """,
                truncate(errorMessage, 4000),
                id
        );
    }

    public void markFailed(UUID id, String errorMessage) {
        jdbc.update(
                """
                UPDATE onchain_consent_outbox
                SET status = 'FAILED', error_message = ?
                WHERE id = ?
                """,
                truncate(errorMessage, 4000),
                id
        );
    }

    public int countRetry(UUID id) {
        Integer n = jdbc.queryForObject(
                "SELECT retry_count FROM onchain_consent_outbox WHERE id = ?",
                Integer.class,
                id
        );
        return n != null ? n : 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record PendingOnchainRow(
            UUID id,
            UUID consentId,
            UUID personId,
            String anchorType,
            String consentJson
    ) {}
}

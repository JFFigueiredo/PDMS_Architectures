package ipl.pdms.ledger;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ConsentLedgerAnchorRepository {

    private final JdbcTemplate jdbcTemplate;

    public String findLatestChainHash(UUID consentId) {
        return jdbcTemplate.query(
                """
                SELECT chain_hash
                FROM consent_ledger_anchor
                WHERE consent_id = ?
                ORDER BY anchored_at DESC
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("chain_hash") : null,
                consentId
        );
    }

    public void saveAnchor(
            UUID id,
            UUID consentId,
            UUID personId,
            String anchorType,
            String payloadHash,
            String previousHash,
            String chainHash,
            Instant anchoredAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO consent_ledger_anchor
                (id, consent_id, person_id, anchor_type, payload_hash, previous_hash, chain_hash, anchored_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (consent_id, anchor_type, payload_hash) DO NOTHING
                """,
                id,
                consentId,
                personId,
                anchorType,
                payloadHash,
                previousHash,
                chainHash,
                Timestamp.from(anchoredAt)
        );
    }
}

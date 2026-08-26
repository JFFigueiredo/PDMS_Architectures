package ipl.pdms.snapshot.service;

import ipl.pdms.person.PersonData;
import ipl.pdms.snapshot.repository.SchemaSnapshotWriter;
import ipl.pdms.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for automatically creating person snapshots.
 * Snapshots capture the complete state of a person at a specific point in time.
 *
 * This service creates snapshots synchronously when Person or Consent data changes via:
 * - PersonChangeEventService (triggers on person create/update)
 * - ConsentChangeEventService (triggers on consent create/update/delete)
 *
 * For reading snapshots (CQRS Query Side), use PersonSnapshotQueryService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonSnapshotService {

    private final OutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;
    private final SchemaSnapshotWriter normalizedSnapshotWriter;

    /**
     * Creates a snapshot synchronously.
     * This is a synchronous operation - blocks until snapshot is created.
     *
     * @param personData the PersonData to snapshot
     * @return Map with snapshotId and personId
     */
    @Transactional
    public Map<String, Object> createSnapshot(PersonData personData) {
        try {
            Map<String, Object> snapshotResult = createSnapshotFromPersonData(personData);
            saveSnapshotEventToOutbox(personData, snapshotResult);
            log.debug("Successfully created snapshot for person {}", personData.id());
            return snapshotResult;
        } catch (Exception e) {
            log.error("Failed to create snapshot for person {}", personData.id(), e);
            throw new RuntimeException("Failed to create snapshot for person " + personData.id(), e);
        }
    }

    @Transactional
    public Map<String, Object> createSnapshotFromPersonData(PersonData personData) {
        UUID personId = personData.id();

        deleteOldSnapshots(personId);

        Map<String, Object> result = new HashMap<>();
        result.put("personId", personId.toString());

        String snapshotId = createSnapshotForSchema(normalizedSnapshotWriter, personId, personData);
        result.put("snapshotId", snapshotId);

        return result;
    }


    private String createSnapshotForSchema(SchemaSnapshotWriter snapshotWriter, UUID personId, PersonData personData) {
        try {
            ipl.pdms.snapshot.domain.PersonSnapshot snapshot = ipl.pdms.snapshot.domain.PersonSnapshot.create(personId, personData);
            log.debug("Creating snapshot {} for person {}", snapshot.snapshotId(), personId);
            snapshotWriter.save(snapshot);
            log.debug("Successfully saved snapshot {} to database", snapshot.snapshotId());
            return snapshot.snapshotId().toString();
        } catch (Exception e) {
            log.error("Failed to create snapshot for person {}: {}", personId, e.getMessage(), e);
            throw new RuntimeException("Failed to create snapshot for person " + personId, e);
        }
    }


    private void deleteOldSnapshots(UUID personId) {
        try {
            jdbcTemplate.update("DELETE FROM person_snapshot WHERE person_id = ?", personId);
        } catch (Exception e) {
            log.warn("Failed to delete old snapshots for person {}, continuing anyway", personId, e);
        }
    }

    private void saveSnapshotEventToOutbox(PersonData personData, Map<String, Object> snapshotResult) {
        try {
            UUID personId = personData.id();
            UUID snapshotId = extractSnapshotId(snapshotResult);

            Map<String, Object> event = new HashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("eventType", "PERSON_SNAPSHOT_CREATED");
            event.put("timestamp", Instant.now().toString());
            event.put("personId", personId.toString());
            event.put("version", personData.version());
            if (snapshotId != null) event.put("snapshotId", snapshotId.toString());
            event.put("snapshotTimestamp", Instant.now().toString());
            event.put("data", toMap(personData));

            outboxService.saveEvent("PERSON", personId.toString(), "PERSON_SNAPSHOT_CREATED", event);
            log.debug("Saved snapshot event for person {} to outbox", personId);
        } catch (Exception e) {
            log.error("Failed to save snapshot event to outbox for person {}", personData.id(), e);
        }
    }

    private UUID extractSnapshotId(Map<String, Object> snapshotResult) {
        try {
            Object snapshotIdObj = snapshotResult.get("snapshotId");
            if (snapshotIdObj != null) {
                String snapshotIdStr = snapshotIdObj.toString();
                if (!snapshotIdStr.startsWith("FAILED")) {
                    return UUID.fromString(snapshotIdStr);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract snapshot ID from result", e);
        }
        return null;
    }

    private Map<String, Object> toMap(PersonData personData) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", personData.id().toString());
        map.put("status", personData.status());
        map.put("version", personData.version());
        map.put("createdAt", personData.createdAt().toString());
        map.put("updatedAt", personData.updatedAt().toString());
        map.put("userAccounts", personData.userAccounts());
        map.put("contactIdentities", personData.identities());
        map.put("addresses", personData.addresses());
        map.put("preferences", personData.preferences());
        return map;
    }
}


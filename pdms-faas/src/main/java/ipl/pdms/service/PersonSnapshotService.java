package ipl.pdms.service;

import ipl.pdms.db.mapper.SchemaSnapshotWriter;
import ipl.pdms.domain.PersonData;
import ipl.pdms.domain.PersonSnapshot;
import ipl.pdms.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Timer;
import ipl.pdms.metrics.GdprMetrics;

/**
 * Service for creating and reading person snapshots using normalized database schema.
 * Snapshots capture the complete state of a person at a specific point in time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonSnapshotService {
    
    private final OutboxService outboxService;
    private final JdbcTemplate jdbcTemplate;
    private final SchemaSnapshotWriter normalizedSnapshotWriter;
    private final GdprMetrics gdprMetrics;

    /**
     * Creates a snapshot of the old version and publishes it to Kafka via outbox.
     * This is the only place where snapshot publishing should happen.
     * Uses outbox pattern to avoid blocking the request thread.
     * 
     * @param personData the PersonData to snapshot
     */
    @Transactional
    public void createAndPublishSnapshot(PersonData personData) {
        try {
            Map<String, Object> snapshotResult = createSnapshotFromPersonData(personData);
            saveSnapshotEventToOutbox(personData, snapshotResult);
            log.debug("Successfully created snapshot for person {}", personData.id());
        } catch (Exception e) {
            log.error("Failed to create and publish snapshot for person {}", personData.id(), e);
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

    /**
     * Reads the latest snapshot for a person from normalized schema.
     * 
     * @param personId the person ID
     * @return the latest person snapshot, or null if not found
     */
    public PersonSnapshot readLatestSnapshot(UUID personId) {
        Timer.Sample sample = gdprMetrics.startDsarTimer();
        try {
            PersonSnapshot snapshot = normalizedSnapshotWriter.readLatest(personId);
            if (snapshot != null) {
                log.debug("Found latest snapshot for person {} in normalized schema", personId);
            }
            gdprMetrics.dataExportRequested();
            return snapshot;
        } catch (Exception e) {
            log.warn("Failed to read latest snapshot from normalized schema for person {}", personId, e);
            return null;
        } finally {
            gdprMetrics.stopDsarTimer(sample);
        }
    }

    private String createSnapshotForSchema(SchemaSnapshotWriter snapshotWriter, UUID personId, PersonData personData) {
        try {
            PersonSnapshot snapshot = PersonSnapshot.create(personId, personData);
            log.debug("Creating snapshot {} for person {}", snapshot.snapshotId(), personId);
            snapshotWriter.save(snapshot);
            log.debug("Successfully saved snapshot {} to database", snapshot.snapshotId());
            return snapshot.snapshotId().toString();
        } catch (Exception e) {
            log.error("Failed to create snapshot for person {}: {}", personId, e.getMessage(), e);
            throw new RuntimeException("Failed to create snapshot for person " + personId, e);
        }
    }

    /**
     * Saves snapshot event to outbox for async publishing to Kafka.
     * This avoids blocking the request thread.
     */
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
            if (snapshotId != null) {
                event.put("snapshotId", snapshotId.toString());
            }
            event.put("snapshotTimestamp", Instant.now().toString());
            event.put("data", toMap(personData));
            
            // Save to outbox - will be published asynchronously by OutboxService
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

    private void deleteOldSnapshots(UUID personId) {
        try {
            jdbcTemplate.update("DELETE FROM person_snapshot WHERE person_id = ?", personId);
        } catch (Exception e) {
            log.warn("Failed to delete old snapshots for person {}, continuing anyway", personId, e);
        }
    }
}


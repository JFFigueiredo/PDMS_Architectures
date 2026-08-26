package ipl.pdms.snapshot.service;

import ipl.pdms.snapshot.domain.PersonSnapshot;
import ipl.pdms.snapshot.repository.SchemaSnapshotWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

import io.micrometer.core.instrument.Timer;
import ipl.pdms.snapshot.metrics.GdprMetrics;

/**
 * Query service for person snapshots (CQRS Query Side).
 * Handles all read operations for snapshots.
 * Separated from command operations to follow CQRS pattern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonSnapshotQueryService {
    
    private final SchemaSnapshotWriter snapshotWriter;
    private final GdprMetrics gdprMetrics;

    /**
     * Reads a snapshot by its ID.
     * 
     * @param snapshotId the snapshot ID
     * @return the person snapshot, or null if not found
     */
    public PersonSnapshot readSnapshot(UUID snapshotId) {
        try {
            PersonSnapshot snapshot = snapshotWriter.read(snapshotId);
            if (snapshot != null) {
                log.debug("Found snapshot {} in normalized schema", snapshotId);
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("Failed to read snapshot {} from normalized schema", snapshotId, e);
            return null;
        }
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
            PersonSnapshot snapshot = snapshotWriter.readLatest(personId);
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

    /**
     * Finds person ID by email using only local snapshot data. Does not call the Person service.
     */
    public UUID findPersonIdByEmail(String email) {
        return snapshotWriter.findPersonIdByEmail(email);
    }

    /**
     * Finds person ID by phone using only local snapshot data. Does not call the Person service.
     */
    public UUID findPersonIdByPhone(String phone) {
        return snapshotWriter.findPersonIdByPhone(phone);
    }
}


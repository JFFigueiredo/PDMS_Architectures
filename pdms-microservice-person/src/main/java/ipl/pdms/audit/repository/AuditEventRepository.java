package ipl.pdms.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for audit events.
 * Provides methods to query audit logs for compliance and forensic purposes.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<ipl.pdms.audit.domain.AuditEvent, UUID> {
    
    /**
     * Find all events for a specific entity
     */
    List<ipl.pdms.audit.domain.AuditEvent> findByEntityTypeAndEntityIdOrderByEventTimestampAsc(String entityType, UUID entityId);
    
    /**
     * Find all events by event type
     */
    List<ipl.pdms.audit.domain.AuditEvent> findByEventTypeOrderByEventTimestampDesc(String eventType);
    
    /**
     * Find all events by user ID
     */
    List<ipl.pdms.audit.domain.AuditEvent> findByUserIdOrderByEventTimestampDesc(String userId);
    
    /**
     * Find events within a time range
     */
    List<ipl.pdms.audit.domain.AuditEvent> findByEventTimestampBetweenOrderByEventTimestampDesc(Instant start, Instant end);
}


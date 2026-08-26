package ipl.pdms.audit.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA Entity for audit events.
 * Stores all user operations in an append-only, tamper-evident manner.
 * This is the primary source of truth for audit logs.
 */
@Entity
@Table(name = "audit_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    
    /**
     * Type of event (e.g., PERSON_CREATED, PERSON_UPDATED, CONSENT_CREATED)
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;
    
    /**
     * Timestamp when the event occurred
     */
    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;
    
    /**
     * User ID who performed the operation (from JWT token)
     */
    @Column(name = "user_id", length = 255)
    private String userId;
    
    /**
     * Type of entity affected (e.g., PERSON, CONSENT, SNAPSHOT)
     */
    @Column(name = "entity_type", length = 100)
    private String entityType;
    
    /**
     * ID of the entity affected
     */
    @Column(name = "entity_id")
    private UUID entityId;
    
    /**
     * HTTP method used (GET, POST, PUT, DELETE)
     */
    @Column(name = "http_method", length = 10)
    private String httpMethod;
    
    /**
     * Request path/endpoint
     */
    @Column(name = "request_path", length = 500)
    private String requestPath;
    
    /**
     * IP address of the client
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;
    
    /**
     * Additional event data stored as JSONB for flexibility
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", columnDefinition = "jsonb")
    private Map<String, Object> eventData;
    
    /**
     * Status of the operation (SUCCESS, FAILURE)
     */
    @Column(name = "status", length = 20)
    private String status;
    
    /**
     * Error message if operation failed
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}



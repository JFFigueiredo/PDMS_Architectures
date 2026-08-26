package ipl.pdms.audit.repository;

import ipl.pdms.audit.repository.AuditSchemaWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * Writer for normalized schema audit events.
 * Uses JdbcTemplate to save to the audit_event table.
 */
@Component
@RequiredArgsConstructor
public class NormalizedAuditWriter implements AuditSchemaWriter {
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    @Override
    public void save(ipl.pdms.audit.domain.AuditEvent auditEvent) {
        String eventDataJson = null;
        if (auditEvent.getEventData() != null && !auditEvent.getEventData().isEmpty()) {
            try {
                eventDataJson = objectMapper.writeValueAsString(auditEvent.getEventData());
            } catch (Exception e) {
            }
        }
        
        jdbcTemplate.update("""
            INSERT INTO audit_event (
                id, event_type, event_timestamp, user_id, entity_type, entity_id,
                http_method, request_path, ip_address, event_data, status, error_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """,
            auditEvent.getId(),
            auditEvent.getEventType(),
            Timestamp.from(auditEvent.getEventTimestamp()),
            auditEvent.getUserId(),
            auditEvent.getEntityType(),
            auditEvent.getEntityId(),
            auditEvent.getHttpMethod(),
            auditEvent.getRequestPath(),
            auditEvent.getIpAddress(),
            eventDataJson,
            auditEvent.getStatus(),
            auditEvent.getErrorMessage()
        );
    }
}



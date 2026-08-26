package ipl.pdms.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified audit service: saves to DB first (source of truth), then publishes to Kafka.
 * Uses normalized database schema.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {
    
    private static final String KAFKA_TOPIC = "pdms.audit.events";
    
    private final List<AuditSchemaWriter> auditSchemaWriters;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;
    
    /**
     * Records an audit event. Saves to DB, publishes to Kafka after commit.
     * Uses REQUIRES_NEW to ensure audit failures don't rollback main transaction.
     * Made async to avoid blocking the main request thread.
     */
    @Async
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void auditEvent(AuditEventType eventType, String entityType, UUID entityId,
                          String[] requestInfo, Map<String, Object> eventData) {
        String httpMethod = requestInfo != null && requestInfo.length > 0 ? requestInfo[0] : null;
        String requestPath = requestInfo != null && requestInfo.length > 1 ? requestInfo[1] : null;
        String ipAddress = requestInfo != null && requestInfo.length > 2 ? requestInfo[2] : null;
        auditEventInternal(eventType, entityType, entityId, httpMethod, requestPath, ipAddress, eventData);
    }

    // Overload for backward compatibility - extracts request info in request thread (request must not be used in @Async)
    public void auditEvent(AuditEventType eventType, String entityType, UUID entityId,
                          HttpServletRequest request, Map<String, Object> eventData) {
        String httpMethod = request != null ? request.getMethod() : null;
        String requestPath = request != null ? request.getRequestURI() : null;
        String ipAddress = request != null ? extractIpAddress(request) : null;
        auditEvent(eventType, entityType, entityId, new String[]{httpMethod, requestPath, ipAddress}, eventData);
    }

    private void auditEventInternal(AuditEventType eventType, String entityType, UUID entityId,
                                   String httpMethod, String requestPath, String ipAddress, Map<String, Object> eventData) {
        try {
            String userId = extractUserId();
            
            UUID eventId = UUID.randomUUID();
            
            AuditEvent auditEvent = AuditEvent.builder()
                    .id(eventId)
                    .eventType(eventType.getValue())
                    .eventTimestamp(Instant.now())
                    .userId(userId)
                    .entityType(entityType)
                    .entityId(entityId)
                    .httpMethod(httpMethod)
                    .requestPath(requestPath)
                    .ipAddress(ipAddress)
                    .eventData(eventData != null ? eventData : new HashMap<>())
                    .status("SUCCESS")
                    .build();
            
            for (AuditSchemaWriter writer : auditSchemaWriters) {
                try {
                    writer.save(auditEvent);
                } catch (Exception e) {
                    log.error("Failed to save audit event to schema: {}", writer.getClass().getSimpleName(), e);
                }
            }
            
            eventPublisher.publishEvent(new AuditEventSavedEvent(auditEvent));
            
        } catch (Exception e) {
            log.error("Failed to record audit event: {} for {}:{}", eventType, entityType, entityId, e);
        }
    }
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishToKafka(AuditEventSavedEvent event) {
        AuditEvent ae = event.auditEvent();
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("eventId", ae.getId().toString());
            msg.put("eventType", ae.getEventType());
            msg.put("eventTimestamp", ae.getEventTimestamp().toString());
            msg.put("userId", ae.getUserId());
            msg.put("entityType", ae.getEntityType());
            msg.put("entityId", ae.getEntityId() != null ? ae.getEntityId().toString() : null);
            msg.put("httpMethod", ae.getHttpMethod());
            msg.put("requestPath", ae.getRequestPath());
            msg.put("ipAddress", ae.getIpAddress());
            msg.put("eventData", ae.getEventData());
            
            String key = ae.getEntityId() != null ? ae.getEntityId().toString() : ae.getId().toString();
            kafkaTemplate.send(KAFKA_TOPIC, key, msg);
        } catch (Exception e) {
            log.error("Failed to publish audit event to Kafka: {}", ae.getId(), e);
        }
    }
    
    private String extractUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception e) {
        }
        return "SYSTEM";
    }
    
    private String extractIpAddress(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isEmpty()) return xri;
        return request.getRemoteAddr();
    }
    
   public record AuditEventSavedEvent(AuditEvent auditEvent) {}
}


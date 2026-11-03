package ipl.pdms.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Set;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {
    
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NamedParameterJdbcTemplate named;
    
    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }
    
    public void save(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(mapper.writeValueAsString(payload));
            
            jdbc.update("INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, processing_status) VALUES (?, ?, ?, ?, ?, 'PENDING')",
                UUID.randomUUID(), aggregateType, aggregateId, eventType, pgObject);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }
    
    public List<Map<String, Object>> findPending(int limit, Set<String> allowedTypes) {
        if (allowedTypes.isEmpty()) return List.of();
        return named.query("SELECT id, aggregate_id, event_type, payload FROM outbox WHERE processing_status = 'PENDING' AND event_type IN (:types) ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED",
            Map.of("types", allowedTypes, "limit", limit),
            (rs, rowNum) -> {
                try {
                    UUID id = rs.getObject("id", UUID.class);
                    Map<String, Object> event = new HashMap<>();
                    event.put("id", id);
                    event.put("aggregateId", rs.getString("aggregate_id"));
                    event.put("eventType", rs.getString("event_type"));
                    event.put("payload", mapper.readValue(rs.getString("payload"), Map.class));
                    jdbc.update("UPDATE outbox SET processing_status = 'PROCESSING' WHERE id = ?", id);
                    return event;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }
    
    public void markCompleted(UUID id) {
        jdbc.update("UPDATE outbox SET processing_status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
    
    public void markFailed(UUID id) {
        jdbc.update("UPDATE outbox SET processing_status = 'FAILED', processed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
    
    /**
     * Delete an event by ID.
     * Used to immediately remove successfully processed events.
     */
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM outbox WHERE id = ?", id);
    }
    
    /**
     * Reset events that have been stuck in PROCESSING status for too long.
     * This can happen if the application crashed while processing events.
     * 
     * @param timeoutMinutes how many minutes an event can be in PROCESSING status before being reset
     * @return number of events reset
     */
    public int resetStuckProcessingEvents(int timeoutMinutes, Set<String> allowedTypes) {
        if (allowedTypes.isEmpty()) return 0;
        return named.update("""
            UPDATE outbox SET processing_status = 'PENDING', processed_at = NULL
            WHERE processing_status = 'PROCESSING' AND event_type IN (:types)
              AND created_at < CURRENT_TIMESTAMP - (INTERVAL '1 minute' * :timeout)
            """, Map.of("types", allowedTypes, "timeout", timeoutMinutes));
    }

    public int retryFailedEvents(int limit, Set<String> allowedTypes) {
        if (allowedTypes.isEmpty()) return 0;
        return named.update("""
            UPDATE outbox SET processing_status = 'PENDING', processed_at = NULL
            WHERE processing_status = 'FAILED' AND event_type IN (:types)
              AND id IN (
                SELECT id FROM outbox
                WHERE processing_status = 'FAILED' AND event_type IN (:types)
                  AND processed_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                ORDER BY processed_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
              )
            """, Map.of("types", allowedTypes, "limit", limit));
    }
}

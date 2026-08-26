package ipl.pdms.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {
    
    private final OutboxRepository repo;
    private final KafkaTemplate<String, Object> kafka;
    
    @Value("${outbox.batch-size:1000}")
    private int batchSize;
    
    @Value("${outbox.processing-timeout-minutes:5}")
    private int processingTimeoutMinutes;
    
    public void saveEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        try {
            repo.save(aggregateType, aggregateId, eventType, payload);
            log.debug("Saved event to outbox: {} for aggregate {}", eventType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to save event to outbox: {} for aggregate {}", eventType, aggregateId, e);
            throw new RuntimeException("Failed to save outbox event", e);
        }
    }
    
    @Scheduled(fixedDelay = 100)
    @Transactional
    public void processOutboxEvents() {
        try {
            List<Map<String, Object>> pendingEvents = repo.findPending(batchSize);
            if (pendingEvents.isEmpty()) {
                return;
            }
            
            log.info("Processing {} outbox events", pendingEvents.size());
            int successCount = 0;
            int failureCount = 0;
            
            for (Map<String, Object> event : pendingEvents) {
                UUID id = (UUID) event.get("id");
                String eventType = event.get("eventType").toString();
                String aggregateId = event.get("aggregateId").toString();
                String topic = determineTopic(eventType);
                
                try {
                    // Send to Kafka synchronously
                    CompletableFuture<SendResult<String, Object>> sendFuture = kafka.send(topic, aggregateId, event.get("payload"));
                    sendFuture.get(10, TimeUnit.SECONDS); // Wait for completion
                    
                    // Mark as completed and delete immediately in the same transaction
                    repo.markCompleted(id);
                    repo.deleteById(id);
                    successCount++;
                } catch (Exception e) {
                    log.error("Failed to publish event {} (type: {}, topic: {})", id, eventType, topic, e);
                    repo.markFailed(id);
                    failureCount++;
                }
            }
            
            if (successCount > 0 || failureCount > 0) {
                log.info("Outbox processing complete: {} succeeded, {} failed", successCount, failureCount);
            }
        } catch (Exception e) {
            log.error("Error processing outbox events", e);
        }
    }
    
    /**
     * Reset events that have been stuck in PROCESSING status for too long.
     * This can happen if the application crashed while processing events.
     */
    @Scheduled(fixedDelay = 60000) // Run every minute
    public void resetStuckProcessingEvents() {
        try {
            int resetCount = repo.resetStuckProcessingEvents(processingTimeoutMinutes);
            if (resetCount > 0) {
                log.warn("Reset {} events that were stuck in PROCESSING status", resetCount);
            }
        } catch (Exception e) {
            log.error("Error resetting stuck processing events", e);
        }
    }
    
    /**
     * Retry failed events (with a limit to avoid infinite retries).
     */
    @Scheduled(fixedDelay = 300000) // Run every 5 minutes
    public void retryFailedEvents() {
        try {
            int retryCount = repo.retryFailedEvents(100); // Retry up to 100 failed events
            if (retryCount > 0) {
                log.info("Retrying {} failed outbox events", retryCount);
            }
        } catch (Exception e) {
            log.error("Error retrying failed events", e);
        }
    }
    
    private String determineTopic(String eventType) {
        if (eventType.contains("SNAPSHOT")) {
            return "pdms.person.snapshot";
        } else if (eventType.contains("PERSON")) {
            return "pdms.person.change";
        } else {
            return "pdms.events";
        }
    }
}

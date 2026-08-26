package ipl.pdms.consent.service;

import ipl.pdms.consent.client.SnapshotServiceClient;
import ipl.pdms.consent.domain.Consent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentChangeEventService {
    
    private static final String CHANGE_EVENT_TOPIC = "pdms.consent.change";
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SnapshotServiceClient snapshotServiceClient;
    
    public void publishConsentCreatedEvent(UUID personId, Consent consent) {
        try {
            Map<String, Object> changeEvent = buildChangeEvent("CONSENT_CREATED", personId, consent);
            publishChangeEvent(personId, changeEvent);
            publishPersonSnapshotAfterConsentChange(personId);
        } catch (Exception e) {
            log.error("Failed to publish consent created change event for consent {} of person {}", consent.id(), personId, e);
        }
    }
    
    public void publishConsentUpdatedEvent(UUID personId, Consent oldConsent, Consent newConsent) {
        try {
            Map<String, Object> changeEvent = buildChangeEvent("CONSENT_UPDATED", personId, calculateConsentChanges(oldConsent, newConsent));
            publishChangeEvent(personId, changeEvent);
            publishPersonSnapshotAfterConsentChange(personId);
        } catch (Exception e) {
            log.error("Failed to publish consent updated change event for consent {} of person {}", newConsent.id(), personId, e);
        }
    }
    
    public void publishConsentDeletedEvent(UUID personId, UUID consentId) {
        try {
            Map<String, Object> deletionEvent = new HashMap<>();
            deletionEvent.put("eventId", UUID.randomUUID().toString());
            deletionEvent.put("eventType", "CONSENT_DELETED");
            deletionEvent.put("timestamp", Instant.now().toString());
            deletionEvent.put("personId", personId.toString());
            deletionEvent.put("consentId", consentId.toString());
            deletionEvent.put("removedAt", Instant.now().toString());
            
            publishChangeEvent(personId, deletionEvent);
            publishPersonSnapshotAfterConsentChange(personId);
        } catch (Exception e) {
            log.error("Failed to publish consent deleted event for consent {} of person {}", consentId, personId, e);
        }
    }
    
    @Async
    private void publishPersonSnapshotAfterConsentChange(UUID personId) {
        snapshotServiceClient.createSnapshotAsync(personId);
    }
    
    private Map<String, Object> buildChangeEvent(String eventType, UUID personId, Object data) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("timestamp", Instant.now().toString());
        event.put("personId", personId.toString());
        event.put("data", data);
        return event;
    }
    
    private Map<String, Object> calculateConsentChanges(Consent oldConsent, Consent newConsent) {
        Map<String, Object> changes = new HashMap<>();
        if (!oldConsent.consentType().equals(newConsent.consentType())) changes.put("consentType", newConsent.consentType());
        if (!oldConsent.granted().equals(newConsent.granted())) changes.put("granted", newConsent.granted());
        if (oldConsent.grantedAt() != null && newConsent.grantedAt() != null && !oldConsent.grantedAt().equals(newConsent.grantedAt())) {
            changes.put("grantedAt", newConsent.grantedAt().toString());
        } else if (oldConsent.grantedAt() == null && newConsent.grantedAt() != null) {
            changes.put("grantedAt", newConsent.grantedAt().toString());
        }
        if (oldConsent.expiresAt() != null && newConsent.expiresAt() != null && !oldConsent.expiresAt().equals(newConsent.expiresAt())) {
            changes.put("expiresAt", newConsent.expiresAt().toString());
        } else if (oldConsent.expiresAt() == null && newConsent.expiresAt() != null) {
            changes.put("expiresAt", newConsent.expiresAt().toString());
        }
        if (oldConsent.isActive() != null && newConsent.isActive() != null && !oldConsent.isActive().equals(newConsent.isActive())) {
            changes.put("isActive", newConsent.isActive());
        } else if (oldConsent.isActive() == null && newConsent.isActive() != null) {
            changes.put("isActive", newConsent.isActive());
        }
        if (!oldConsent.version().equals(newConsent.version())) changes.put("version", newConsent.version());
        return changes;
    }
    
    private void publishChangeEvent(UUID personId, Map<String, Object> event) {
        kafkaTemplate.send(CHANGE_EVENT_TOPIC, personId.toString(), event);
        log.info("Published change event for person {} to topic {}", personId, CHANGE_EVENT_TOPIC);
    }
}


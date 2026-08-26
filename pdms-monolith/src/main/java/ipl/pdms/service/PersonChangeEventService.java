package ipl.pdms.service;

import ipl.pdms.domain.PersonData;
import ipl.pdms.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonChangeEventService {
    
    private final PersonSnapshotService personSnapshotService;
    private final OutboxService outboxService;
    
    public void publishPersonCreatedEvent(PersonData personData) {
        try {
            Map<String, Object> changeEvent = buildChangeEvent("PERSON_CREATED", personData.id(), personData);
            outboxService.saveEvent("PERSON", personData.id().toString(), "PERSON_CREATED", changeEvent);
            // Create snapshot synchronously, Kafka publishing is async via OutboxService
            personSnapshotService.createAndPublishSnapshot(personData);
        } catch (Exception e) {
            log.error("Failed to save person created event to outbox for person {}", personData.id(), e);
        }
    }
    
    public void publishPersonUpdatedEvent(PersonData oldPersonData, PersonData newPersonData) {
        try {
            Map<String, Object> changes = calculateChanges(oldPersonData, newPersonData);
            Map<String, Object> changeEvent = buildChangeEvent("PERSON_UPDATED", newPersonData.id(), changes);
            outboxService.saveEvent("PERSON", newPersonData.id().toString(), "PERSON_UPDATED", changeEvent);
            // Create snapshot synchronously, Kafka publishing is async via OutboxService
            personSnapshotService.createAndPublishSnapshot(newPersonData);
        } catch (Exception e) {
            log.error("Failed to save person updated event to outbox for person {}", newPersonData.id(), e);
        }
    }
    
    public void publishPersonDeletedEvent(UUID personId) {
        try {
            Map<String, Object> deletionEvent = new HashMap<>();
            deletionEvent.put("eventId", UUID.randomUUID().toString());
            deletionEvent.put("eventType", "PERSON_DELETED");
            deletionEvent.put("timestamp", Instant.now().toString());
            deletionEvent.put("personId", personId.toString());
            deletionEvent.put("removedAt", Instant.now().toString());
            
            outboxService.saveEvent("PERSON", personId.toString(), "PERSON_DELETED", deletionEvent);
        } catch (Exception e) {
            log.error("Failed to save person deleted event to outbox for person {}", personId, e);
        }
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
    
    private Map<String, Object> calculateChanges(PersonData oldData, PersonData newData) {
        Map<String, Object> changes = new HashMap<>();
        
        if (!oldData.status().equals(newData.status())) {
            changes.put("status", newData.status());
        }
        if (!oldData.version().equals(newData.version())) {
            changes.put("version", newData.version());
        }
        if (!oldData.updatedAt().equals(newData.updatedAt())) {
            changes.put("updatedAt", newData.updatedAt().toString());
        }
        
        if (hasUserAccountsChanged(oldData, newData)) {
            changes.put("userAccounts", newData.userAccounts());
        }
        if (hasIdentitiesChanged(oldData, newData)) {
            changes.put("contactIdentities", newData.identities());
        }
        if (hasAddressesChanged(oldData, newData)) {
            changes.put("addresses", newData.addresses());
        }
        if (hasPreferencesChanged(oldData, newData)) {
            changes.put("preferences", newData.preferences());
        }
        
        return changes;
    }
    
    private boolean hasUserAccountsChanged(PersonData oldData, PersonData newData) {
        if (oldData.userAccounts() == null && newData.userAccounts() == null) return false;
        if (oldData.userAccounts() == null || newData.userAccounts() == null) return true;
        return !oldData.userAccounts().equals(newData.userAccounts());
    }
    
    private boolean hasIdentitiesChanged(PersonData oldData, PersonData newData) {
        if (oldData.identities() == null && newData.identities() == null) return false;
        if (oldData.identities() == null || newData.identities() == null) return true;
        return !oldData.identities().equals(newData.identities());
    }
    
    private boolean hasAddressesChanged(PersonData oldData, PersonData newData) {
        if (oldData.addresses() == null && newData.addresses() == null) return false;
        if (oldData.addresses() == null || newData.addresses() == null) return true;
        return !oldData.addresses().equals(newData.addresses());
    }
    
    private boolean hasPreferencesChanged(PersonData oldData, PersonData newData) {
        if (oldData.preferences() == null && newData.preferences() == null) return false;
        if (oldData.preferences() == null || newData.preferences() == null) return true;
        return !oldData.preferences().equals(newData.preferences());
    }
    
}


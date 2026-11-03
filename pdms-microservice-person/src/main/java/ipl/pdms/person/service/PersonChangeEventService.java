package ipl.pdms.person.service;

import ipl.pdms.person.client.SnapshotServiceClient;
import ipl.pdms.person.domain.PersonData;
import ipl.pdms.shared.outbox.OutboxService;
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

    private final SnapshotServiceClient snapshotServiceClient;
    private final OutboxService outboxService;

    public void publishPersonCreatedEvent(PersonData personData) {
        try {
            // Create snapshot synchronously via API call
            Map<String, Object> personDataMap = toMap(personData);
            snapshotServiceClient.createSnapshot(personData.id(), personDataMap);
            log.debug("Successfully created snapshot for person {} after creation", personData.id());
        } catch (Exception e) {
            log.error("Failed to create snapshot for person {} after creation", personData.id(), e);
            // Don't throw - allow person creation to succeed even if snapshot fails
        }
        try {
            Map<String, Object> changeEvent = buildChangeEvent("PERSON_CREATED", personData.id(), personData);
            outboxService.saveEvent("PERSON", personData.id().toString(), "PERSON_CREATED", changeEvent);
        } catch (Exception e) {
            log.error("Failed to save person created event to outbox for person {}", personData.id(), e);
        }
    }

    public void publishPersonUpdatedEvent(PersonData oldPersonData, PersonData newPersonData) {
        try {
            // Create snapshot synchronously via API call
            Map<String, Object> personDataMap = toMap(newPersonData);
            snapshotServiceClient.createSnapshot(newPersonData.id(), personDataMap);
            log.debug("Successfully created snapshot for person {} after update", newPersonData.id());
        } catch (Exception e) {
            log.error("Failed to create snapshot for person {} after update", newPersonData.id(), e);
            // Don't throw - allow person update to succeed even if snapshot fails
        }
        try {
            Map<String, Object> changes = calculateChanges(oldPersonData, newPersonData);
            Map<String, Object> changeEvent = buildChangeEvent("PERSON_UPDATED", newPersonData.id(), changes);
            outboxService.saveEvent("PERSON", newPersonData.id().toString(), "PERSON_UPDATED", changeEvent);
        } catch (Exception e) {
            log.error("Failed to save person updated event to outbox for person {}", newPersonData.id(), e);
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

    private Map<String, Object> toMap(PersonData personData) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", personData.id().toString());
        map.put("status", personData.status());
        map.put("version", personData.version());
        map.put("createdAt", personData.createdAt().toString());
        map.put("updatedAt", personData.updatedAt().toString());
        map.put("userAccounts", personData.userAccounts());
        map.put("identities", personData.identities()); // Use "identities" to match PersonData field name
        map.put("addresses", personData.addresses());
        map.put("preferences", personData.preferences());
        return map;
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


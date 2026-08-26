package ipl.pdms.snapshot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import ipl.pdms.person.PersonData;
import ipl.pdms.snapshot.service.PersonSnapshotQueryService;
import ipl.pdms.snapshot.service.PersonSnapshotService;
import ipl.pdms.snapshot.client.PersonServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/snapshot")
@RequiredArgsConstructor
@Slf4j
public class SnapshotController {
    
    private final PersonSnapshotQueryService queryService;
    private final PersonSnapshotService snapshotService;
    private final PersonServiceClient personServiceClient;
    private final ObjectMapper objectMapper;
    
    @GetMapping("/person/{personId}/latest")
    public ResponseEntity<Map<String, Object>> getLatestSnapshot(@PathVariable UUID personId) {
        try {
            var snapshot = queryService.readLatestSnapshot(personId);
            if (snapshot == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No snapshot found", "personId", personId.toString()));
            }
            return ResponseEntity.ok(buildSnapshotResponse(snapshot));
        } catch (Exception e) {
            log.error("Error reading latest snapshot: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to read latest snapshot", "message", e.getMessage()));
        }
    }
    
    private Map<String, Object> toPersonDataMap(PersonData personData) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", personData.id().toString());
        map.put("status", personData.status());
        map.put("version", personData.version());
        map.put("createdAt", personData.createdAt());
        map.put("updatedAt", personData.updatedAt());
        
        if (personData.userAccounts() != null && !personData.userAccounts().isEmpty()) {
            map.put("userAccounts", personData.userAccounts().stream()
                    .map(ua -> {
                        Map<String, Object> uaMap = new java.util.HashMap<>();
                        uaMap.put("id", ua.id().toString());
                        uaMap.put("personId", ua.personId().toString());
                        uaMap.put("status", ua.status());
                        uaMap.put("type", ua.type());
                        uaMap.put("firstName", ua.firstName());
                        uaMap.put("lastName", ua.lastName());
                        uaMap.put("surname", ua.surname());
                        uaMap.put("taxNumber", ua.taxNumber());
                        uaMap.put("dateOfBirth", ua.dateOfBirth());
                        uaMap.put("gender", ua.gender());
                        uaMap.put("nationality", ua.nationality());
                        uaMap.put("createdAt", ua.createdAt());
                        uaMap.put("updatedAt", ua.updatedAt());
                        return uaMap;
                    })
                    .toList());
        }
        
        if (personData.identities() != null && !personData.identities().isEmpty()) {
            map.put("identities", personData.identities().stream()
                    .map(ci -> {
                        Map<String, Object> ciMap = new java.util.HashMap<>();
                        ciMap.put("id", ci.id().toString());
                        ciMap.put("personId", ci.personId().toString());
                        ciMap.put("type", ci.type() != null ? ci.type().name() : null);
                        ciMap.put("value", ci.value());
                        ciMap.put("verificationDate", ci.verificationDate());
                        ciMap.put("primary", ci.primary());
                        ciMap.put("createdAt", ci.createdAt());
                        ciMap.put("updatedAt", ci.updatedAt());
                        return ciMap;
                    })
                    .toList());
        }
        
        if (personData.addresses() != null && !personData.addresses().isEmpty()) {
            map.put("addresses", personData.addresses().stream()
                    .map(addr -> {
                        Map<String, Object> addrMap = new java.util.HashMap<>();
                        addrMap.put("id", addr.id().toString());
                        addrMap.put("personId", addr.personId().toString());
                        addrMap.put("addressType", addr.addressType());
                        addrMap.put("country", addr.country());
                        addrMap.put("postalCode", addr.postalCode());
                        addrMap.put("city", addr.city());
                        addrMap.put("street", addr.street());
                        addrMap.put("streetNumber", addr.streetNumber());
                        addrMap.put("flat", addr.flat());
                        addrMap.put("companyName", addr.companyName());
                        addrMap.put("additionalInformation", addr.additionalInformation());
                        addrMap.put("verifiedAddress", addr.verifiedAddress());
                        addrMap.put("isPrimary", addr.isPrimary());
                        addrMap.put("createdAt", addr.createdAt());
                        addrMap.put("updatedAt", addr.updatedAt());
                        return addrMap;
                    })
                    .toList());
        }
        
        if (personData.preferences() != null) {
            var pref = personData.preferences();
            Map<String, Object> prefMap = new java.util.HashMap<>();
            prefMap.put("id", pref.id().toString());
            prefMap.put("personId", pref.personId().toString());
            prefMap.put("language", pref.language());
            prefMap.put("nickname", pref.nickname());
            prefMap.put("createdAt", pref.createdAt());
            prefMap.put("updatedAt", pref.updatedAt());
            map.put("preferences", prefMap);
        }
        
        return map;
    }
    
    @PostMapping("/person/{personId}")
    public ResponseEntity<Map<String, Object>> createSnapshot(
            @PathVariable UUID personId,
            @RequestBody(required = false) Map<String, Object> personDataMap) {
        try {
            // If personData is provided, use it; otherwise fetch from person service synchronously
            if (personDataMap == null || personDataMap.isEmpty()) {
                Map<String, Object> response = personServiceClient.getPersonData(personId);
                if (response == null || response.isEmpty()) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "Person not found", "personId", personId.toString()));
                }
                
                // Extract personData if response is in snapshot format (has nested personData)
                if (response.containsKey("personData")) {
                    personDataMap = asStringObjectMap(response.get("personData"));
                    if (personDataMap == null || personDataMap.isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", "Person data not found", "personId", personId.toString()));
                    }
                } else {
                    personDataMap = response;
                }
            }
            
            // Convert Map to PersonData using ObjectMapper (serialize to JSON then deserialize for proper type handling)
            String json = objectMapper.writeValueAsString(personDataMap);
            PersonData personData = objectMapper.readValue(json, PersonData.class);
            
            // Ensure required fields have defaults if missing
            if (personData.status() == null || personData.version() == null || 
                personData.createdAt() == null || personData.updatedAt() == null) {
                personData = PersonData.builder()
                    .id(personData.id())
                    .status(personData.status() != null ? personData.status() : "ACTIVE")
                    .version(personData.version() != null ? personData.version() : 1)
                    .createdAt(personData.createdAt() != null ? personData.createdAt() : java.time.Instant.now())
                    .updatedAt(personData.updatedAt() != null ? personData.updatedAt() : java.time.Instant.now())
                    .userAccounts(personData.userAccounts())
                    .identities(personData.identities())
                    .addresses(personData.addresses())
                    .preferences(personData.preferences())
                    .build();
            }
            
            // Create snapshot synchronously
            Map<String, Object> result = snapshotService.createSnapshot(personData);
            
            return ResponseEntity.ok(Map.of(
                    "snapshotId", result.get("snapshotId").toString(),
                    "personId", result.get("personId").toString(),
                    "message", "Snapshot created successfully"
            ));
        } catch (Exception e) {
            log.error("Error creating snapshot: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create snapshot", "message", e.getMessage()));
        }
    }
    
    @GetMapping("/by-email/{email}")
    public ResponseEntity<Map<String, Object>> getSnapshotByEmail(@PathVariable String email) {
        return getSnapshotByIdentifier(() -> queryService.findPersonIdByEmail(email), "email", email);
    }
    
    @GetMapping("/by-phone/{phone}")
    public ResponseEntity<Map<String, Object>> getSnapshotByPhone(@PathVariable String phone) {
        return getSnapshotByIdentifier(() -> queryService.findPersonIdByPhone(phone), "phone", phone);
    }
    
    /**
     * Resolves identifier (email/phone) to personId using only local snapshot data, then returns latest snapshot.
     * Does not call the Person service.
     */
    private ResponseEntity<Map<String, Object>> getSnapshotByIdentifier(
            java.util.function.Supplier<UUID> personIdSupplier, String identifierType, String identifier) {
        try {
            UUID personId = personIdSupplier.get();
            if (personId == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No snapshot found", identifierType, identifier));
            }
            
            var snapshot = queryService.readLatestSnapshot(personId);
            if (snapshot == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No snapshot found", identifierType, identifier));
            }
            
            Map<String, Object> response = buildSnapshotResponse(snapshot);
            response.put(identifierType, identifier);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting snapshot by {}: {}", identifierType, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get snapshot", "message", e.getMessage(), identifierType, identifier));
        }
    }
    
    private Map<String, Object> buildSnapshotResponse(ipl.pdms.snapshot.domain.PersonSnapshot snapshot) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("snapshotId", snapshot.snapshotId().toString());
        response.put("personId", snapshot.personId().toString());
        response.put("snapshotTimestamp", snapshot.snapshotTimestamp().toString());
        if (snapshot.personData() != null) {
            response.put("personData", toPersonDataMap(snapshot.personData()));
        }
        return response;
    }

    private Map<String, Object> asStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> normalized = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }
}

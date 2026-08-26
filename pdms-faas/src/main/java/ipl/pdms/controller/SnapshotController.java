package ipl.pdms.controller;

import ipl.pdms.domain.PersonData;
import ipl.pdms.domain.PersonSnapshot;
import ipl.pdms.service.PersonService;
import ipl.pdms.service.PersonSnapshotService;
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
    
    private final PersonSnapshotService personSnapshotService;
    private final PersonService personService;
    
    @GetMapping("/person/{personId}/latest")
    public ResponseEntity<Map<String, Object>> getLatestSnapshot(@PathVariable UUID personId) {
        try {
            PersonSnapshot snapshot = personSnapshotService.readLatestSnapshot(personId);
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
    
    @GetMapping("/by-email/{email}")
    public ResponseEntity<Map<String, Object>> getSnapshotByEmail(@PathVariable String email) {
        log.debug("Received request to get snapshot by email: {}", email);
        try {
            PersonData personData = personService.findPersonByEmail(email);
            if (personData == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Person not found", "email", email));
            }
            
            UUID personId = personData.id();
            PersonSnapshot snapshot = personSnapshotService.readLatestSnapshot(personId);
            if (snapshot == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No snapshot found", "email", email));
            }
            
            Map<String, Object> response = buildSnapshotResponse(snapshot);
            response.put("email", email);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting snapshot by email: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get snapshot by email", "message", e.getMessage(), "email", email));
        }
    }
    
    @GetMapping("/by-phone/{phone}")
    public ResponseEntity<Map<String, Object>> getSnapshotByPhone(@PathVariable String phone) {
        log.debug("Received request to get snapshot by phone: {}", phone);
        try {
            PersonData personData = personService.findPersonByPhone(phone);
            if (personData == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Person not found", "phone", phone));
            }
            
            UUID personId = personData.id();
            PersonSnapshot snapshot = personSnapshotService.readLatestSnapshot(personId);
            if (snapshot == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No snapshot found", "phone", phone));
            }
            
            Map<String, Object> response = buildSnapshotResponse(snapshot);
            response.put("phone", phone);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting snapshot by phone: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get snapshot by phone", "message", e.getMessage(), "phone", phone));
        }
    }
    
    private Map<String, Object> buildSnapshotResponse(PersonSnapshot snapshot) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("snapshotId", snapshot.snapshotId().toString());
        response.put("personId", snapshot.personId().toString());
        response.put("snapshotTimestamp", snapshot.snapshotTimestamp().toString());
        if (snapshot.personData() != null) {
            response.put("personData", toPersonDataMapForSnapshot(snapshot.personData()));
        }
        return response;
    }
    
    private Map<String, Object> toPersonDataMapForSnapshot(PersonData personData) {
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
}

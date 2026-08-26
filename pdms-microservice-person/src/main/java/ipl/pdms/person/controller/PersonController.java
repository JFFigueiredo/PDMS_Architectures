package ipl.pdms.person.controller;

import ipl.pdms.person.controller.dto.CreatePersonRequestDto;
import ipl.pdms.person.service.PersonService;
import ipl.pdms.person.client.SnapshotServiceClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for person operations.
 * <p>
 * Flow:
 * 1. Receives DTO (CreatePersonRequestDto) from API request
 * 2. Service converts DTO → Person domain object (ipl.pdms.person.domain.PersonData)
 * 3. Service handles persistence of ipl.pdms.person.domain.PersonData
 */
@RestController
@RequestMapping({"/api/person", "/api/mapper/person"})
@RequiredArgsConstructor
@Slf4j
public class PersonController {

    private final PersonService personService;
    private final SnapshotServiceClient snapshotServiceClient;

    /**
     * Creates a person.
     * <p>
     * Flow: DTO → Person domain object → Persistence
     *
     * @param createPersonRequestDto the DTO object containing person data
     * @return response containing the person ID and save status
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPerson(
            @Valid @RequestBody CreatePersonRequestDto createPersonRequestDto) {

        log.debug("Received request to create person");

        try {
            UUID personId = personService.savePerson(createPersonRequestDto);

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("personId", personId.toString()));
        } catch (Exception e) {
            log.error("Error creating person: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to create person",
                            "message", e.getMessage()
                    ));
        }
    }

    @GetMapping("/{personId}")
    public ResponseEntity<Map<String, Object>> getPerson(@PathVariable UUID personId) {
        try {
            ipl.pdms.person.domain.PersonData personData = personService.readPerson(personId);
            if (personData == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Person not found", "personId", personId.toString()));
            }
            return ResponseEntity.ok(toResponseMap(personData));
        } catch (Exception e) {
            log.error("Error reading person: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to read person", "message", e.getMessage(), "personId", personId.toString()));
        }
    }


    @PutMapping("/{personId}")
    public ResponseEntity<Map<String, Object>> updatePerson(
            @PathVariable UUID personId,
            @Valid @RequestBody CreatePersonRequestDto dto) {
        try {
            if (personService.readPerson(personId) == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Person not found", "personId", personId.toString()));
            }
            return ResponseEntity.ok(personService.updatePerson(personId, dto));
        } catch (Exception e) {
            log.error("Error updating person: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to update person", "message", e.getMessage(), "personId", personId.toString()));
        }
    }

    @DeleteMapping("/{personId}")
    public ResponseEntity<Map<String, Object>> deletePerson(@PathVariable UUID personId) {
        try {
            if (personService.readPerson(personId) == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Person not found", "personId", personId.toString()));
            }
            return ResponseEntity.ok(personService.deletePerson(personId));
        } catch (Exception e) {
            log.error("Error deleting person: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to delete person", "message", e.getMessage(), "personId", personId.toString()));
        }
    }

    /**
     * Health check endpoint to verify the controller is working.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "PersonController"));
    }

    private Map<String, Object> toResponseMap(ipl.pdms.person.domain.PersonData personData) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("personId", personData.id().toString());
        response.put("status", personData.status());
        response.put("version", personData.version());
        response.put("createdAt", personData.createdAt());
        response.put("updatedAt", personData.updatedAt());

        if (personData.userAccounts() != null && !personData.userAccounts().isEmpty()) {
            response.put("userAccounts", personData.userAccounts().stream()
                    .map(ua -> Map.of(
                            "id", ua.id().toString(),
                            "type", ua.type(),
                            "firstName", orEmpty(ua.firstName()),
                            "lastName", orEmpty(ua.lastName()),
                            "surname", orEmpty(ua.surname()),
                            "gender", orEmpty(ua.gender()),
                            "nationality", orEmpty(ua.nationality())
                    ))
                    .toList());
        }

        if (personData.identities() != null && !personData.identities().isEmpty()) {
            response.put("contactIdentities", personData.identities().stream()
                    .map(ci -> Map.of(
                            "id", ci.id().toString(),
                            "type", ci.type() != null ? ci.type().name() : "",
                            "value", orEmpty(ci.value()),
                            "primary", ci.primary() != null ? ci.primary() : false
                    ))
                    .toList());
        }

        if (personData.addresses() != null && !personData.addresses().isEmpty()) {
            response.put("addresses", personData.addresses().stream()
                    .map(addr -> Map.of(
                            "id", addr.id().toString(),
                            "addressType", orEmpty(addr.addressType()),
                            "country", orEmpty(addr.country()),
                            "city", orEmpty(addr.city()),
                            "postalCode", orEmpty(addr.postalCode()),
                            "street", orEmpty(addr.street()),
                            "isPrimary", addr.isPrimary() != null ? addr.isPrimary() : false
                    ))
                    .toList());
        }

        if (personData.preferences() != null) {
            var pref = personData.preferences();
            response.put("preferences", Map.of(
                    "id", pref.id().toString(),
                    "language", orEmpty(pref.language()),
                    "nickname", orEmpty(pref.nickname())
            ));
        }

        return response;
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }

}


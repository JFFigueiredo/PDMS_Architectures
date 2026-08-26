package ipl.pdms;

import ipl.pdms.audit.domain.AuditEvent;
import ipl.pdms.audit.repository.AuditEventRepository;
import ipl.pdms.audit.domain.AuditEventType;
import ipl.pdms.person.controller.dto.CreatePersonRequestDto;
import ipl.pdms.shared.security.TestJwtTokenGenerator;
import ipl.pdms.shared.security.TestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GDPR compliance requirements in Person Service.
 * <p>
 * User Management & Identity (Articles 5, 15, 16, 17, 25, 30, 32):
 * - UUID-based identification (Article 5(1)(c))
 * - Timestamps and version tracking (Articles 5, 30)
 * - DSAR rectification (Article 16)
 * - DSAR erasure (Article 17)
 * - DSAR access (Article 15)
 * <p>
 * Logging & Auditability (Articles 5, 30, 32):
 * - Audit all Person data actions (Article 12, 30)
 * <p>
 * Security of Processing (Article 32):
 * - Enforce authentication for all personal data endpoints
 * - Log security events (failed logins, suspicious patterns)
 * <p>
 * Data Minimization (Article 5(1)(c)):
 * - Support minimal data collection (optional fields)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class GDPRComplianceTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void shouldUseUuidAsPrimaryIdentifier() {
        // GDPR Article 5(1)(c) - Data minimization: Use UUIDs, not sensitive data as keys
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("John")
                        .lastName("Doe")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String personIdStr = createResponse.getBody().get("personId").toString();
        UUID personId = UUID.fromString(personIdStr);

        // Verify UUID format (not email or other sensitive data)
        assertThat(personId).isNotNull();
        assertThat(personIdStr).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void shouldTrackTimestampsAndVersionForAccountability() {
        // GDPR Articles 5, 30 - Accountability: Record creation/update timestamps and version
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Jane")
                        .lastName("Smith")
                        .build())
                .build();

        // Create person
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Read person and verify timestamps and version
        ResponseEntity<Map<String, Object>> read1 = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(read1.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> personData = read1.getBody();
        assertThat(personData).isNotNull();
        assertThat(personData).containsKey("createdAt");
        assertThat(personData).containsKey("updatedAt");
        assertThat(personData).containsKey("version");
        assertThat(personData.get("createdAt")).isNotNull();
        assertThat(personData.get("updatedAt")).isNotNull();
        assertThat(personData.get("version")).isNotNull();

        String initialCreatedAt = personData.get("createdAt").toString();
        Integer initialVersion = (Integer) personData.get("version");

        // Update person
        var update = CreatePersonRequestDto.builder()
                .status("INACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Jane")
                        .lastName("Smith")
                        .build())
                .build();

        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Verify version increments and createdAt is preserved
        ResponseEntity<Map<String, Object>> read2 = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        Map<String, Object> updatedData = read2.getBody();
        assertThat(updatedData.get("createdAt").toString()).isEqualTo(initialCreatedAt);
        assertThat((Integer) updatedData.get("version")).isGreaterThan(initialVersion);
    }

    @Test
    void shouldSupportRectificationWithDsarType() {
        // GDPR Article 16 - Right to rectification: Standard update endpoint supports rectification requests
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Incorrect")
                        .lastName("Name")
                        .build())
                .build();

        // Create person
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Rectify person via standard update endpoint (GDPR Article 16)
        var rectification = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Corrected")
                        .lastName("Name")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> rectificationResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.PUT,
                new HttpEntity<>(rectification, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(rectificationResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = rectificationResponse.getBody();
        assertThat(result).containsKey("personId");
        assertThat(result).containsKey("status");
    }

    @Test
    void shouldSupportDsarErasure() {
        // GDPR Article 17 - Right to erasure ("Right to be forgotten"): Standard delete endpoint supports erasure requests
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("ToErase")
                        .lastName("Person")
                        .build())
                .build();

        // Create person
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Erase person via standard delete endpoint (GDPR Article 17)
        ResponseEntity<Map<String, Object>> erasureResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(erasureResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = erasureResponse.getBody();
        assertThat(result).containsKey("personId");

        // Verify person is deleted
        ResponseEntity<Map<String, Object>> verifyResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldSupportDsarAccess() {
        // GDPR Article 15 - Right of access: Standard GET endpoint supports data access requests
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Access")
                        .lastName("Test")
                        .build())
                .build();

        // Create person
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Access via standard GET endpoint (GDPR Article 15)
        ResponseEntity<Map<String, Object>> accessResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(accessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = accessResponse.getBody();
        assertThat(result).isNotNull();
        assertThat(result).containsKey("personId");
        assertThat(result).containsKey("createdAt");
        assertThat(result).containsKey("updatedAt");
        assertThat(result).containsKey("version");
    }

    @Test
    void shouldLogFailedLoginAttempts() {
        // GDPR Article 32 - Security of processing: Log security events (failed logins)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid-token-12345"); // Invalid token

        // Attempt to access protected endpoint with invalid token
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Verify request was rejected
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Wait for async audit processing with retry logic
        List<AuditEvent> securityEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEventTypeOrderByEventTimestampDesc(
                        AuditEventType.SECURITY_LOGIN_FAILED.getValue()),
                5000, 200);
        assertThat(securityEvents).isNotEmpty();

        // Verify the failed login event details
        AuditEvent failedLoginEvent = securityEvents.stream()
                .filter(e -> "SECURITY".equals(e.getEntityType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Failed login event not found"));

        assertThat(failedLoginEvent.getEventType()).isEqualTo(AuditEventType.SECURITY_LOGIN_FAILED.getValue());
        assertThat(failedLoginEvent.getEntityType()).isEqualTo("SECURITY");
        assertThat(failedLoginEvent.getEventData()).isNotNull();
        assertThat(failedLoginEvent.getEventData()).containsKey("reason");
        assertThat(failedLoginEvent.getEventData().get("reason")).isIn("Invalid token", "Expired token");
        assertThat(failedLoginEvent.getIpAddress()).isNotNull();
    }

    @Test
    void shouldLogSuspiciousAccessPatterns() {
        // GDPR Article 32 - Security of processing: Log suspicious patterns (multiple failed attempts)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid-token-12345"); // Invalid token

        // Make multiple failed login attempts from the same "client" (simulated by same headers)
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange(
                    getBaseUrl() + "/api/person",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
        }

        // Wait for async audit processing with retry logic
        List<AuditEvent> suspiciousEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEventTypeOrderByEventTimestampDesc(
                        AuditEventType.SECURITY_SUSPICIOUS_ACCESS.getValue()),
                5000, 200);
        assertThat(suspiciousEvents).isNotEmpty();

        // Verify the suspicious access event details
        AuditEvent suspiciousEvent = suspiciousEvents.stream()
                .filter(e -> "SECURITY".equals(e.getEntityType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Suspicious access event not found"));

        assertThat(suspiciousEvent.getEventType()).isEqualTo(AuditEventType.SECURITY_SUSPICIOUS_ACCESS.getValue());
        assertThat(suspiciousEvent.getEntityType()).isEqualTo("SECURITY");
        assertThat(suspiciousEvent.getEventData()).isNotNull();
        assertThat(suspiciousEvent.getEventData()).containsKey("pattern");
        assertThat(suspiciousEvent.getEventData().get("pattern")).isEqualTo("Multiple failed attempts");
        assertThat(suspiciousEvent.getEventData()).containsKey("ipAddress");
        assertThat(suspiciousEvent.getEventData()).containsKey("failedAttempts");
        assertThat(suspiciousEvent.getEventData().get("failedAttempts")).isEqualTo(5);
    }

    @Test
    void shouldAuditAllPersonOperations() {
        // GDPR Articles 5, 30 - Logging & Auditability: All Person data actions are logged
        HttpHeaders headers = createHeaders();

        // Create person
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Audit")
                        .lastName("Test")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Wait for async audit processing with retry logic
        List<AuditEvent> createEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("PERSON", personId),
                5000, 200);
        assertThat(createEvents).isNotEmpty();
        assertThat(createEvents).anyMatch(e ->
            AuditEventType.PERSON_CREATED.getValue().equals(e.getEventType()) &&
            personId.equals(e.getEntityId()) &&
            ("test-user".equals(e.getUserId()) || "SYSTEM".equals(e.getUserId()))
        );

        // Update person
        var update = CreatePersonRequestDto.builder()
                .status("INACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Audit")
                        .lastName("Test")
                        .build())
                .build();

        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Wait for async audit processing with retry logic
        List<AuditEvent> updateEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("PERSON", personId),
                5000, 200);
        assertThat(updateEvents).anyMatch(e -> 
            AuditEventType.PERSON_UPDATED.getValue().equals(e.getEventType()) &&
            personId.equals(e.getEntityId())
        );

        // Delete person
        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Wait for async audit processing with retry logic
        List<AuditEvent> deleteEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("PERSON", personId),
                5000, 200);
        assertThat(deleteEvents).anyMatch(e -> 
            AuditEventType.PERSON_DELETED.getValue().equals(e.getEventType()) &&
            personId.equals(e.getEntityId())
        );
    }

    @Test
    void shouldEnforceAuthenticationForPersonalDataEndpoints() {
        // GDPR Article 32 - Security of processing: Enforce authentication for all personal data endpoints
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No authentication token provided

        // Attempt to access person endpoint without authentication
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Verify request was rejected (401 or 403)
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldSupportDataMinimizationWithOptionalFields() {
        // GDPR Article 5(1)(c) - Data minimization: Only necessary fields are required
        HttpHeaders headers = createHeaders();

        // Create person with minimal required fields only
        var minimalPerson = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Minimal")
                        .lastName("Data")
                        .build())
                // No addresses, no contact identities, no preferences, no consents
                .build();

        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(minimalPerson, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Verify person was created successfully with minimal data
        ResponseEntity<Map<String, Object>> readResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> personData = readResponse.getBody();
        assertThat(personData).isNotNull();
        assertThat(personData.get("personId")).isEqualTo(personId.toString());
        // Optional fields can be null/empty - this demonstrates data minimization
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));
        return headers;
    }
    
    /**
     * Helper method to wait for audit events with retry logic.
     * This handles async processing delays.
     */
    private List<AuditEvent> waitForAuditEvents(java.util.function.Supplier<List<AuditEvent>> eventSupplier, 
                                                 long maxWaitMs, long retryIntervalMs) {
        long startTime = System.currentTimeMillis();
        List<AuditEvent> events = eventSupplier.get();
        
        while (events.isEmpty() && (System.currentTimeMillis() - startTime) < maxWaitMs) {
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            events = eventSupplier.get();
        }
        
        return events;
    }
}

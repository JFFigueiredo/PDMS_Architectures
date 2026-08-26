package ipl.pdms;

import ipl.pdms.audit.AuditEvent;
import ipl.pdms.audit.AuditEventRepository;
import ipl.pdms.audit.AuditEventType;
import ipl.pdms.controller.dto.CreateConsentRequestDto;
import ipl.pdms.controller.dto.CreatePersonRequestDto;
import ipl.pdms.domain.Consent;
import ipl.pdms.security.TestJwtTokenGenerator;
import ipl.pdms.security.TestSecurityConfiguration;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GDPR compliance requirements.
 * <p>
 * User Management & Identity (Articles 5, 15, 16, 17, 25, 30, 32):
 * - UUID-based identification (Article 5(1)(c))
 * - Timestamps and version tracking (Articles 5, 30)
 * - DSAR rectification (Article 16)
 * - DSAR erasure (Article 17)
 * - DSAR access (Article 15)
 * <p>
 * Consent Management (Articles 6, 7, 12–14, 25, 30):
 * - Record consent per user, per purpose (Article 7)
 * - Capture timestamps, lawful basis, privacy policy version, consent source, withdrawal status (Articles 7, 30)
 * - Allow withdrawal of consent at any time (Article 7)
 * - Maintain immutable audit trail for all consent actions (Article 5, 30)
 * - Support proof of consent for regulatory inspection (Articles 7, 30)
 * <p>
 * Logging & Auditability (Articles 5, 30, 32):
 * - Audit all Person data actions (Article 12, 30)
 * - Audit all Consent operations (Article 5, 30)
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
    void shouldRecordConsentWithTimestampsAndMetadata() {
        // GDPR Articles 7, 30 - Consent recording: Capture timestamp, lawful basis (consent_type), 
        // privacy policy version, consent source, withdrawal status
        HttpHeaders headers = createHeaders();

        // Create person first
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Consent")
                        .lastName("Test")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createPersonResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createPersonResponse.getBody().get("personId").toString());

        // Create consent with all required metadata
        var consentRequest = new CreateConsentRequestDto(
                "MARKETING", // consent_type (purpose/legal basis)
                true, // granted
                Instant.now().plusSeconds(86400), // expiresAt (1 day from now)
                "WEB_FORM", // consent_method (consent source)
                "v1.0", // consent_version (privacy policy version)
                null // withdrawalReason
        );

        ResponseEntity<Consent> createConsentResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(consentRequest, headers),
                Consent.class
        );

        assertThat(createConsentResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Consent consent = createConsentResponse.getBody();
        assertThat(consent).isNotNull();
        assertThat(consent.personId()).isEqualTo(personId);
        assertThat(consent.consentType()).isEqualTo("MARKETING");
        assertThat(consent.granted()).isTrue();
        assertThat(consent.grantedAt()).isNotNull(); // Timestamp captured
        assertThat(consent.consentMethod()).isEqualTo("WEB_FORM"); // Consent source
        assertThat(consent.consentVersion()).isEqualTo("v1.0"); // Privacy policy version
        assertThat(consent.isActive()).isTrue(); // Withdrawal status
        assertThat(consent.withdrawnAt()).isNull(); // Not withdrawn
    }

    @Test
    void shouldAllowWithdrawalOfConsentAtAnyTime() {
        // GDPR Article 7 - Right to withdraw consent: Allow withdrawal at any time
        HttpHeaders headers = createHeaders();

        // Create person
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Withdraw")
                        .lastName("Test")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createPersonResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createPersonResponse.getBody().get("personId").toString());

        // Create consent
        var consentRequest = new CreateConsentRequestDto(
                "ANALYTICS",
                true,
                null,
                "MOBILE_APP",
                "v1.0",
                null
        );

        ResponseEntity<Consent> createConsentResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(consentRequest, headers),
                Consent.class
        );
        UUID consentId = createConsentResponse.getBody().id();

        // Withdraw consent
        ResponseEntity<Consent> withdrawResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents/" + consentId + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("withdrawalReason", "User requested withdrawal"), headers),
                Consent.class
        );

        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Consent withdrawn = withdrawResponse.getBody();
        assertThat(withdrawn).isNotNull();
        assertThat(withdrawn.granted()).isFalse();
        assertThat(withdrawn.isActive()).isFalse(); // Withdrawal status updated
        assertThat(withdrawn.withdrawnAt()).isNotNull(); // Withdrawal timestamp captured
        assertThat(withdrawn.withdrawalReason()).isEqualTo("User requested withdrawal");
    }

    @Test
    void shouldTrackConsentStatusAndVersion() {
        // GDPR Articles 7, 30 - Consent tracking: Track status (is_active) and version for accountability
        HttpHeaders headers = createHeaders();

        // Create person
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Status")
                        .lastName("Test")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createPersonResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createPersonResponse.getBody().get("personId").toString());

        // Create consent
        var consentRequest = new CreateConsentRequestDto(
                "DATA_PROCESSING",
                true,
                null,
                "EMAIL",
                "v1.0",
                null
        );

        ResponseEntity<Consent> createConsentResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(consentRequest, headers),
                Consent.class
        );
        Consent initialConsent = createConsentResponse.getBody();
        UUID consentId = initialConsent.id();
        Integer initialVersion = initialConsent.version();

        // Update consent (should increment version)
        var updateRequest = new CreateConsentRequestDto(
                "DATA_PROCESSING",
                true,
                Instant.now().plusSeconds(172800), // 2 days
                "EMAIL",
                "v1.1", // Updated privacy policy version
                null
        );

        ResponseEntity<Consent> updateResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents/" + consentId,
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                Consent.class
        );

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Consent updated = updateResponse.getBody();
        assertThat(updated.version()).isGreaterThan(initialVersion); // Version incremented
        assertThat(updated.consentVersion()).isEqualTo("v1.1"); // Privacy policy version updated
        assertThat(updated.isActive()).isTrue(); // Status maintained
    }

    @Test
    void shouldSupportProofOfConsentForRegulatoryInspection() {
        // GDPR Articles 7, 30 - Proof of consent: Support retrieval for regulatory inspection
        HttpHeaders headers = createHeaders();

        // Create person
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Proof")
                        .lastName("Test")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createPersonResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createPersonResponse.getBody().get("personId").toString());

        // Create multiple consents
        var consent1 = new CreateConsentRequestDto("MARKETING", true, null, "WEB_FORM", "v1.0", null);
        var consent2 = new CreateConsentRequestDto("ANALYTICS", true, null, "MOBILE_APP", "v1.0", null);

        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(consent1, headers),
                Consent.class
        );

        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(consent2, headers),
                Consent.class
        );

        // Retrieve all consents for proof
        ResponseEntity<List<Consent>> listResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<Consent>>() {
                }
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Consent> consents = listResponse.getBody();
        assertThat(consents).isNotNull();
        assertThat(consents.size()).isGreaterThanOrEqualTo(2);

        // Verify all required fields for proof are present
        Consent consent = consents.stream()
                .filter(c -> "MARKETING".equals(c.consentType()))
                .findFirst()
                .orElseThrow();

        assertThat(consent.personId()).isEqualTo(personId); // Data subject identified
        assertThat(consent.consentType()).isNotNull(); // Purpose/legal basis
        assertThat(consent.grantedAt()).isNotNull(); // Timestamp
        assertThat(consent.consentMethod()).isNotNull(); // Consent source
        assertThat(consent.consentVersion()).isNotNull(); // Privacy policy version
        assertThat(consent.isActive()).isNotNull(); // Withdrawal status
        assertThat(consent.version()).isNotNull(); // Version for tracking
    }

    @Test
    void shouldLogFailedLoginAttempts() {
        // GDPR Article 32 - Security of processing: Log security events (failed logins)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid-token-12345"); // Invalid token
        headers.set("X-Forwarded-For", "10.10.10.10");

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

        // Verify PERSON_CREATED (userId may be SYSTEM when @Async)
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

        // Verify PERSON_UPDATED audit event
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

        // Verify PERSON_DELETED audit event
        assertThat(deleteEvents).anyMatch(e -> 
            AuditEventType.PERSON_DELETED.getValue().equals(e.getEventType()) &&
            personId.equals(e.getEntityId())
        );
    }

    @Test
    void shouldAuditAllConsentOperations() {
        // GDPR Articles 5, 30 - Logging & Auditability: All consent actions are logged
        HttpHeaders headers = createHeaders();

        // Create person
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Consent")
                        .lastName("Audit")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> createPersonResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createPersonResponse.getBody().get("personId").toString());

        // Create consent
        var consentRequest = new CreateConsentRequestDto("MARKETING", true, null, "WEB_FORM", "v1.0", null);
        ResponseEntity<Consent> createConsentResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(consentRequest, headers),
                Consent.class
        );
        UUID consentId = createConsentResponse.getBody().id();

        // Wait for async audit processing with retry logic
        List<AuditEvent> consentEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("CONSENT", personId),
                5000, 200);

        // Verify CONSENT_CREATED audit event (consent events use personId as entityId)
        assertThat(consentEvents).isNotEmpty();
        assertThat(consentEvents).anyMatch(e -> 
            AuditEventType.CONSENT_CREATED.getValue().equals(e.getEventType()) &&
            personId.equals(e.getEntityId())
        );

        // Update consent
        var updateRequest = new CreateConsentRequestDto("MARKETING", true, null, "WEB_FORM", "v1.1", null);
        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents/" + consentId,
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                Consent.class
        );

        // Wait for async audit processing with retry logic
        List<AuditEvent> updateEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("CONSENT", personId),
                5000, 200);

        // Verify CONSENT_UPDATED audit event (consent events use personId as entityId)
        assertThat(updateEvents).anyMatch(e -> 
            AuditEventType.CONSENT_UPDATED.getValue().equals(e.getEventType()) &&
            personId.equals(e.getEntityId())
        );

        // Withdraw consent
        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents/" + consentId + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("withdrawalReason", "Test withdrawal"), headers),
                Consent.class
        );

        // Wait for async audit processing with retry logic
        List<AuditEvent> withdrawEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("CONSENT", personId),
                5000, 200);

        // Verify CONSENT_WITHDRAWN audit event (consent events use personId as entityId)
        assertThat(withdrawEvents).anyMatch(e -> 
            AuditEventType.CONSENT_WITHDRAWN.getValue().equals(e.getEventType()) &&
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

    /** Wait for audit events (same strategy across all projects: 5000 ms max, 200 ms interval). */
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


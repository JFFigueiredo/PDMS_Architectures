package ipl.pdms;

import ipl.pdms.audit.domain.AuditEvent;
import ipl.pdms.audit.repository.AuditEventRepository;
import ipl.pdms.audit.domain.AuditEventType;
import ipl.pdms.consent.controller.dto.CreateConsentRequestDto;
import ipl.pdms.consent.domain.Consent;
import ipl.pdms.shared.security.TestJwtTokenGenerator;
import ipl.pdms.shared.security.TestSecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GDPR compliance requirements in Consent Service.
 * <p>
 * Consent Management (Articles 6, 7, 12–14, 25, 30):
 * - Record consent per user, per purpose (Article 7)
 * - Capture timestamps, lawful basis, privacy policy version, consent source, withdrawal status (Articles 7, 30)
 * - Allow withdrawal of consent at any time (Article 7)
 * - Maintain immutable audit trail for all consent actions (Article 5, 30)
 * - Support proof of consent for regulatory inspection (Articles 7, 30)
 * <p>
 * Logging & Auditability (Articles 5, 30, 32):
 * - Audit all Consent operations (Article 5, 30)
 * <p>
 * Security of Processing (Article 32):
 * - Enforce authentication for all personal data endpoints
 * - Log security events (failed logins, suspicious patterns)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class GDPRComplianceTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRecordConsentWithTimestampsAndMetadata() {
        // GDPR Articles 7, 30 - Consent recording: Capture timestamp, lawful basis (consent_type), 
        // privacy policy version, consent source, withdrawal status
        HttpHeaders headers = createHeaders();
        UUID personId = createPersonInDatabase();

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
        UUID personId = createPersonInDatabase();

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
        UUID personId = createPersonInDatabase();

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
        UUID personId = createPersonInDatabase();

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

        // Attempt to access protected consent endpoint with invalid token
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + UUID.randomUUID() + "/consents",
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
        UUID testPersonId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange(
                    getBaseUrl() + "/api/person/" + testPersonId + "/consents",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
        }

        // Give async tasks time to start and execute
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Wait for async audit processing with retry logic
        List<AuditEvent> suspiciousEvents = waitForAuditEvents(
                () -> auditEventRepository.findByEventTypeOrderByEventTimestampDesc(
                        AuditEventType.SECURITY_SUSPICIOUS_ACCESS.getValue()),
                10000, 200);
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
    void shouldAuditAllConsentOperations() {
        // GDPR Articles 5, 30 - Logging & Auditability: All consent actions are logged
        HttpHeaders headers = createHeaders();
        UUID personId = createPersonInDatabase();

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

        // Attempt to access consent endpoint without authentication
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + UUID.randomUUID() + "/consents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Verify request was rejected (401 or 403)
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));
        return headers;
    }
    
    /**
     * Helper method to create a person directly in the database.
     * Returns the personId.
     * Since consent service doesn't have person creation endpoints, we create persons directly in DB for tests.
     */
    private UUID createPersonInDatabase() {
        UUID personId = UUID.randomUUID();
        Instant now = Instant.now();
        
        // Insert person record
        jdbcTemplate.update(
                "INSERT INTO person (id, status, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                personId, "ACTIVE", 1, Timestamp.from(now), Timestamp.from(now)
        );
        
        // Insert a minimal user account for the person
        UUID userAccountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO user_account (id, person_id, status, type, version, first_name, last_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userAccountId, personId, "ACTIVE", "STANDARD", 1, "Test", "User", Timestamp.from(now), Timestamp.from(now)
        );
        
        return personId;
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

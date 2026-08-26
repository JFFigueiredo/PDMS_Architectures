package ipl.pdms;

import ipl.pdms.audit.domain.AuditEvent;
import ipl.pdms.audit.repository.AuditEventRepository;
import ipl.pdms.audit.domain.AuditEventType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for GDPR compliance requirements in Snapshot Service.
 * <p>
 * Data Access & Historical Records (Articles 5, 15, 30, 32):
 * - Support DSAR access to historical snapshots (Article 15)
 * - Track snapshot timestamps for accountability (Articles 5, 30)
 * - Use UUID-based identification for snapshots (Article 5(1)(c))
 * <p>
 * Logging & Auditability (Articles 5, 30, 32):
 * - Audit all Snapshot operations (Article 5, 30)
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
    void shouldSupportDsarAccess() {
        // GDPR Article 15 - Right of access: Standard GET endpoint supports data access requests for snapshots
        HttpHeaders headers = createHeaders();
        
        // Create a person in the database first
        UUID personId = createPersonInDatabase();
        
        // Create a snapshot for the person
        UUID snapshotId = createSnapshotInDatabase(personId);

        // Access snapshot via standard GET endpoint (GDPR Article 15)
        // Note: The snapshot service exposes /api/snapshot/person/{personId}/latest
        ResponseEntity<Map<String, Object>> accessResponse = restTemplate.exchange(
                getBaseUrl() + "/api/snapshot/person/" + personId + "/latest",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(accessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = accessResponse.getBody();
        assertThat(result).isNotNull();
        assertThat(result).containsKey("snapshotId");
        assertThat(result).containsKey("personId");
        assertThat(result).containsKey("snapshotTimestamp");
        assertThat(result.get("personId")).isEqualTo(personId.toString());
    }

    @Test
    void shouldTrackSnapshotTimestamps() {
        // GDPR Articles 5, 30 - Accountability: Record snapshot timestamps
        HttpHeaders headers = createHeaders();
        
        // Create a person in the database
        UUID personId = createPersonInDatabase();
        
        // Create a snapshot
        UUID snapshotId = createSnapshotInDatabase(personId);

        // Access snapshot and verify timestamp
        ResponseEntity<Map<String, Object>> accessResponse = restTemplate.exchange(
                getBaseUrl() + "/api/snapshot/person/" + personId + "/latest",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(accessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = accessResponse.getBody();
        assertThat(result).isNotNull();
        assertThat(result).containsKey("snapshotTimestamp");
        assertThat(result.get("snapshotTimestamp")).isNotNull();
        
        // Verify timestamp is a valid ISO-8601 format
        String timestampStr = result.get("snapshotTimestamp").toString();
        assertThat(timestampStr).isNotEmpty();
        // Timestamp should be parseable
        Instant.parse(timestampStr);
    }

    @Test
    void shouldUseUuidAsPrimaryIdentifier() {
        // GDPR Article 5(1)(c) - Data minimization: Use UUIDs for snapshot identification
        HttpHeaders headers = createHeaders();
        
        // Create a person in the database
        UUID personId = createPersonInDatabase();
        
        // Create a snapshot
        UUID snapshotId = createSnapshotInDatabase(personId);

        // Access snapshot and verify UUID format
        ResponseEntity<Map<String, Object>> accessResponse = restTemplate.exchange(
                getBaseUrl() + "/api/snapshot/person/" + personId + "/latest",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        assertThat(accessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = accessResponse.getBody();
        assertThat(result).isNotNull();
        assertThat(result).containsKey("snapshotId");
        
        String snapshotIdStr = result.get("snapshotId").toString();
        UUID retrievedSnapshotId = UUID.fromString(snapshotIdStr);
        
        // Verify UUID format (not email or other sensitive data)
        assertThat(retrievedSnapshotId).isNotNull();
        assertThat(snapshotIdStr).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void shouldLogFailedLoginAttempts() {
        // GDPR Article 32 - Security of processing: Log security events (failed logins)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid-token-12345"); // Invalid token

        // Attempt to access protected snapshot endpoint with invalid token
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/snapshot/person/" + UUID.randomUUID() + "/latest",
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
        // Add small delays between requests to ensure they are processed sequentially
        UUID testPersonId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            restTemplate.exchange(
                    getBaseUrl() + "/api/snapshot/person/" + testPersonId + "/latest",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
        }

        // Wait for async audit processing with retry logic (same strategy as all projects)
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
    void shouldEnforceAuthenticationForPersonalDataEndpoints() {
        // GDPR Article 32 - Security of processing: Enforce authentication for all personal data endpoints
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No authentication token provided

        // Attempt to access snapshot endpoint without authentication
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/snapshot/person/" + UUID.randomUUID() + "/latest",
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
     * Since snapshot service doesn't have person creation endpoints, we create persons directly in DB for tests.
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
     * Helper method to create a snapshot directly in the database.
     * Returns the snapshotId.
     */
    private UUID createSnapshotInDatabase(UUID personId) {
        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.now();
        
        // Insert snapshot record
        jdbcTemplate.update(
                "INSERT INTO person_snapshot (snapshot_id, person_id, snapshot_timestamp, created_at, person_status, person_version, person_created_at, person_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotId, personId, Timestamp.from(now), Timestamp.from(now), "ACTIVE", 1, Timestamp.from(now), Timestamp.from(now)
        );
        
        return snapshotId;
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

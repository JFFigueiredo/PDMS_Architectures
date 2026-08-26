package ipl.pdms.ledger;

import ipl.pdms.IntegrationTestBase;
import ipl.pdms.controller.dto.CreatePersonRequestDto;
import ipl.pdms.domain.Consent;
import ipl.pdms.outbox.OutboxService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the real-world flow: consent persisted + audited synchronously,
 * consent integrity anchors written asynchronously via outbox into {@code consent_ledger_anchor}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class ConsentLedgerOutboxIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createConsent_thenProcessOutbox_persistsLedgerAnchorWithExpectedFields() {
        HttpHeaders headers = createHeaders();
        UUID personId = createPerson(headers);

        var body = Map.of(
                "consentType", "MARKETING",
                "granted", true,
                "consentMethod", "WEB",
                "consentVersion", "1.0"
        );

        ResponseEntity<Consent> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Consent.class
        );
        assertThat(createResponse.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(createResponse.getBody()).isNotNull();
        UUID consentId = createResponse.getBody().id();

        assertThat(countPendingOutboxForConsent(consentId)).isPositive();

        outboxService.processOutboxEvents();

        assertThat(countPendingOutboxForConsent(consentId)).isZero();

        List<Map<String, Object>> anchors = fetchAnchors(consentId);
        assertThat(anchors).hasSize(1);
        Map<String, Object> row = anchors.getFirst();
        assertThat(row.get("anchor_type")).isEqualTo("CONSENT_CREATED");
        assertThat(row.get("person_id")).isEqualTo(personId);
        assertThat(row.get("payload_hash")).asString().matches("[a-f0-9]{64}");
        assertThat(row.get("chain_hash")).asString().matches("[a-f0-9]{64}");
        assertThat(row.get("previous_hash")).isNull();
    }

    @Test
    void updateConsent_thenProcessOutbox_appendsSecondAnchorWithLinkedChain() {
        HttpHeaders headers = createHeaders();
        UUID personId = createPerson(headers);

        var createBody = Map.of(
                "consentType", "ANALYTICS",
                "granted", true,
                "consentMethod", "MOBILE_APP",
                "consentVersion", "1.0"
        );
        ResponseEntity<Consent> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents",
                HttpMethod.POST,
                new HttpEntity<>(createBody, headers),
                Consent.class
        );
        UUID consentId = createResponse.getBody().id();
        outboxService.processOutboxEvents();
        assertThat(fetchAnchors(consentId)).hasSize(1);
        String firstChain = (String) fetchAnchors(consentId).getFirst().get("chain_hash");

        var updatePayload = Map.of(
                "consentType", "ANALYTICS",
                "granted", true,
                "consentMethod", "MOBILE_APP",
                "consentVersion", "2.0"
        );

        restTemplate.exchange(
                getBaseUrl() + "/api/person/" + personId + "/consents/" + consentId,
                HttpMethod.PUT,
                new HttpEntity<>(updatePayload, headers),
                Consent.class
        );

        outboxService.processOutboxEvents();

        List<Map<String, Object>> anchors = fetchAnchors(consentId);
        assertThat(anchors).hasSize(2);
        Map<String, Object> updated = anchors.stream()
                .filter(r -> "CONSENT_UPDATED".equals(r.get("anchor_type")))
                .findFirst()
                .orElseThrow();
        assertThat(updated.get("previous_hash")).isEqualTo(firstChain);
        assertThat(updated.get("chain_hash")).asString().matches("[a-f0-9]{64}");
        assertThat(updated.get("payload_hash")).asString().matches("[a-f0-9]{64}");
    }

    private long countPendingOutboxForConsent(UUID consentId) {
        Long n = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM outbox
                WHERE aggregate_id = ? AND processing_status = 'PENDING'
                """,
                Long.class,
                consentId.toString()
        );
        return n != null ? n : 0;
    }

    private List<Map<String, Object>> fetchAnchors(UUID consentId) {
        return jdbcTemplate.query(
                """
                SELECT anchor_type, person_id, payload_hash, previous_hash, chain_hash
                FROM consent_ledger_anchor
                WHERE consent_id = ?
                ORDER BY anchored_at ASC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("anchor_type", rs.getString("anchor_type"));
                    m.put("person_id", rs.getObject("person_id", UUID.class));
                    m.put("payload_hash", rs.getString("payload_hash"));
                    m.put("previous_hash", rs.getString("previous_hash"));
                    m.put("chain_hash", rs.getString("chain_hash"));
                    return m;
                },
                consentId
        );
    }

    private UUID createPerson(HttpHeaders headers) {
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Ledger")
                        .lastName("Test")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/mapper/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(response.getBody()).isNotNull();
        return UUID.fromString(response.getBody().get("personId").toString());
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("ledger-outbox-test"));
        return headers;
    }
}

package ipl.pdms.consent;

import ipl.pdms.IntegrationTestBase;
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
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class ConsentCrudNormalizedTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldCreateAndReadConsent() {
        HttpHeaders headers = createHeaders();

        // Create person first
        UUID personId = createPerson(headers);

        // Create consent
        var consent = Map.of(
            "consentType", "ANALYTICS",
            "granted", true,
            "consentMethod", "MOBILE_APP",
            "consentVersion", "2.0"
        );

        ResponseEntity<Consent> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/person/" + personId + "/consents",
            HttpMethod.POST,
            new HttpEntity<>(consent, headers),
            Consent.class
        );
        assertThat(createResponse.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);

        // Read consents
        ResponseEntity<List<Consent>> readResponse = restTemplate.exchange(
            getBaseUrl() + "/api/person/" + personId + "/consents",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<List<Consent>>() {}
        );
        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readResponse.getBody()).isNotNull();
    }

    @Test
    void shouldDeleteConsent() {
        HttpHeaders headers = createHeaders();

        // Create person first
        UUID personId = createPerson(headers);

        // Create consent
        var consent = Map.of(
            "consentType", "ANALYTICS",
            "granted", true,
            "consentMethod", "MOBILE_APP",
            "consentVersion", "2.0"
        );

        ResponseEntity<Consent> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/person/" + personId + "/consents",
            HttpMethod.POST,
            new HttpEntity<>(consent, headers),
            Consent.class
        );
        UUID consentId = createResponse.getBody().id();

        // Delete consent
        ResponseEntity<Map<String, String>> deleteResponse = restTemplate.exchange(
            getBaseUrl() + "/api/person/" + personId + "/consents/" + consentId,
            HttpMethod.DELETE,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<Map<String, String>>() {}
        );
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID createPerson(HttpHeaders headers) {
        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("John")
                .lastName("Doe")
                .build())
            .build();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return UUID.fromString(response.getBody().get("personId").toString());
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));
        return headers;
    }
}



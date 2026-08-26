package ipl.pdms;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class CrudTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldCreatePerson() {
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("John")
                .lastName("Doe")
                .build())
            .build();

        // Create
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody()).containsKey("personId");
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());
        assertThat(personId).isNotNull();
    }

    @Test
    void shouldCreateAndReadConsent() {
        HttpHeaders headers = createHeaders();

        // Create person first
        UUID personId = createPerson(headers);

        // Create consent
        var consent = Map.of(
            "consentType", "MARKETING",
            "granted", true,
            "consentMethod", "WEB_FORM",
            "consentVersion", "1.0"
        );

        ResponseEntity<Consent> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/person/" + personId + "/consents",
            HttpMethod.POST,
            new HttpEntity<>(consent, headers),
            Consent.class
        );
        
        // Verify creation was successful
        assertThat(createResponse.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().consentType()).isEqualTo("MARKETING");
        assertThat(createResponse.getBody().granted()).isTrue();
        assertThat(createResponse.getBody().personId()).isEqualTo(personId);

        // Read consents
        ResponseEntity<List<Consent>> readResponse = restTemplate.exchange(
            getBaseUrl() + "/api/person/" + personId + "/consents",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<List<Consent>>() {}
        );
        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readResponse.getBody()).isNotNull();
        assertThat(readResponse.getBody()).isNotEmpty();
        assertThat(readResponse.getBody()).anyMatch(c -> 
            "MARKETING".equals(c.consentType()) && 
            c.granted() && 
            personId.equals(c.personId())
        );
    }

    @Test
    void shouldUpdatePerson() {
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Jane")
                .lastName("Smith")
                .build())
            .build();

        // Create
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Update
        var update = CreatePersonRequestDto.builder()
            .status("INACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Jane")
                .lastName("Smith")
                .build())
            .build();

        ResponseEntity<Map<String, Object>> updateResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person/" + personId,
            HttpMethod.PUT,
            new HttpEntity<>(update, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody()).containsKey("personId");
        assertThat(updateResponse.getBody().get("personId")).isEqualTo(personId.toString());
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


package ipl.pdms.person;

import ipl.pdms.IntegrationTestBase;
import ipl.pdms.controller.dto.CreatePersonRequestDto;
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
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class PersonCrudNormalizedTest extends IntegrationTestBase {

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
    void shouldDeletePerson() {
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Bob")
                .lastName("Johnson")
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

        // Delete
        ResponseEntity<Map<String, Object>> deleteResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person/" + personId,
            HttpMethod.DELETE,
            new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteResponse.getBody()).isNotNull();
        assertThat(deleteResponse.getBody()).containsKey("personId");
        assertThat(deleteResponse.getBody().get("personId")).isEqualTo(personId.toString());
    }

    @Test
    void shouldGetPersonByEmail() {
        HttpHeaders headers = createHeaders();

        String email = "test-" + UUID.randomUUID() + "-" + System.currentTimeMillis() + "@example.com";
        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Email")
                .lastName("Test")
                .build())
            .contactIdentities(List.of(
                CreatePersonRequestDto.ContactIdentityDto.builder()
                    .type("EMAIL")
                    .value(email)
                    .primary(true)
                    .build()
            ))
            .build();

        // Create person with email
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Create a snapshot by updating the person.
        var update = CreatePersonRequestDto.builder()
            .status("INACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Email")
                .lastName("Test")
                .build())
            .contactIdentities(List.of(
                CreatePersonRequestDto.ContactIdentityDto.builder()
                    .type("EMAIL")
                    .value(email)
                    .primary(true)
                    .build()
            ))
            .build();
        ResponseEntity<Map<String, Object>> updateResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person/" + personId,
            HttpMethod.PUT,
            new HttpEntity<>(update, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Get person by email
        String encodedEmail = java.net.URLEncoder.encode(email, StandardCharsets.UTF_8);
        RequestEntity<Void> byEmailRequest = RequestEntity
            .get(URI.create(getBaseUrl() + "/api/snapshot/by-email/" + encodedEmail))
            .header(HttpHeaders.AUTHORIZATION, headers.getFirst(HttpHeaders.AUTHORIZATION))
            .build();
        ResponseEntity<Map<String, Object>> getByEmailResponse = restTemplate.exchange(
            byEmailRequest,
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(getByEmailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getByEmailResponse.getBody()).isNotNull();
        assertThat(getByEmailResponse.getBody()).containsKey("personId");
        assertThat(getByEmailResponse.getBody()).containsKey("email");
        assertThat(getByEmailResponse.getBody()).containsKey("personData");
        assertThat(getByEmailResponse.getBody().get("personId")).isEqualTo(personId.toString());
        assertThat(getByEmailResponse.getBody().get("email")).isEqualTo(email);
    }

    @Test
    void shouldGetPersonByPhone() {
        HttpHeaders headers = createHeaders();

        String phone = "+1234567890";
        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Phone")
                .lastName("Test")
                .build())
            .contactIdentities(List.of(
                CreatePersonRequestDto.ContactIdentityDto.builder()
                    .type("PHONE")
                    .value(phone)
                    .primary(true)
                    .build()
            ))
            .build();

        // Create person with phone
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Create a snapshot by updating the person.
        var update = CreatePersonRequestDto.builder()
            .status("INACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Phone")
                .lastName("Test")
                .build())
            .contactIdentities(List.of(
                CreatePersonRequestDto.ContactIdentityDto.builder()
                    .type("PHONE")
                    .value(phone)
                    .primary(true)
                    .build()
            ))
            .build();
        ResponseEntity<Map<String, Object>> updateResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person/" + personId,
            HttpMethod.PUT,
            new HttpEntity<>(update, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Get person by phone
        String encodedPhone = java.net.URLEncoder.encode(phone, StandardCharsets.UTF_8);
        RequestEntity<Void> byPhoneRequest = RequestEntity
            .get(URI.create(getBaseUrl() + "/api/snapshot/by-phone/" + encodedPhone))
            .header(HttpHeaders.AUTHORIZATION, headers.getFirst(HttpHeaders.AUTHORIZATION))
            .build();
        ResponseEntity<Map<String, Object>> getByPhoneResponse = restTemplate.exchange(
            byPhoneRequest,
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(getByPhoneResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getByPhoneResponse.getBody()).isNotNull();
        assertThat(getByPhoneResponse.getBody()).containsKey("personId");
        assertThat(getByPhoneResponse.getBody()).containsKey("phone");
        assertThat(getByPhoneResponse.getBody()).containsKey("personData");
        assertThat(getByPhoneResponse.getBody().get("personId")).isEqualTo(personId.toString());
        assertThat(getByPhoneResponse.getBody().get("phone")).isEqualTo(phone);
    }

    @Test
    void shouldGetLatestSnapshot() {
        HttpHeaders headers = createHeaders();

        var person = CreatePersonRequestDto.builder()
            .status("ACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Snapshot")
                .lastName("Test")
                .build())
            .build();

        // Create person
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Update person to create a snapshot
        var update = CreatePersonRequestDto.builder()
            .status("INACTIVE")
            .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                .type("STANDARD")
                .firstName("Snapshot")
                .lastName("Test")
                .build())
            .build();

        ResponseEntity<Map<String, Object>> updateResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person/" + personId,
            HttpMethod.PUT,
            new HttpEntity<>(update, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Get latest snapshot
        ResponseEntity<Map<String, Object>> snapshotResponse = restTemplate.exchange(
            getBaseUrl() + "/api/snapshot/person/" + personId + "/latest",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(snapshotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshotResponse.getBody()).isNotNull();
        assertThat(snapshotResponse.getBody()).containsKey("snapshotId");
        assertThat(snapshotResponse.getBody()).containsKey("personId");
        assertThat(snapshotResponse.getBody()).containsKey("snapshotTimestamp");
        assertThat(snapshotResponse.getBody()).containsKey("personData");
        assertThat(snapshotResponse.getBody().get("personId")).isEqualTo(personId.toString());
    }

    @Test
    void shouldReturnNotFoundForNonExistentSnapshot() {
        HttpHeaders headers = createHeaders();
        UUID nonExistentPersonId = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> snapshotResponse = restTemplate.exchange(
            getBaseUrl() + "/api/snapshot/person/" + nonExistentPersonId + "/latest",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        assertThat(snapshotResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(snapshotResponse.getBody()).isNotNull();
        assertThat(snapshotResponse.getBody()).containsKey("error");
    }

    @Test
    void shouldReturnHealthStatus() {
        HttpHeaders headers = createHeaders();

        ResponseEntity<Map<String, String>> healthResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person/health",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<Map<String, String>>() {}
        );
        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthResponse.getBody()).isNotNull();
        assertThat(healthResponse.getBody()).containsKey("status");
        assertThat(healthResponse.getBody()).containsKey("service");
        assertThat(healthResponse.getBody().get("status")).isEqualTo("UP");
        assertThat(healthResponse.getBody().get("service")).isEqualTo("PersonController");
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));
        return headers;
    }
}



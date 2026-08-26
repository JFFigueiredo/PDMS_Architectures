package ipl.pdms.security;

import ipl.pdms.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class SecurityTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldAllowRequestWithValidJwtToken() {
        // Given: Valid JWT token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));

        var person = Map.of(
            "status", "ACTIVE",
            "userAccount", Map.of(
                "type", "STANDARD",
                "firstName", "John",
                "lastName", "Doe"
            )
        );

        // When: Make request with valid token
        ResponseEntity<?> response = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            response = restTemplate.exchange(
                getBaseUrl() + "/api/mapper/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                Object.class
            );
            if (!response.getStatusCode().is5xxServerError()) {
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Then: Request succeeds
        assertThat(response).isNotNull();
        ResponseEntity<?> finalResponse = response;
        assertThat(finalResponse.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
    }

    @Test
    void shouldRejectRequestWithInvalidJwtToken() {
        // Given: Invalid JWT token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("invalid-token");

        var person = Map.of(
            "status", "ACTIVE",
            "userAccount", Map.of(
                "type", "STANDARD",
                "firstName", "John",
                "lastName", "Doe"
            )
        );

        // When: Make request with invalid token
        ResponseEntity<?> response = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            Object.class
        );

        // Then: Request is rejected with 401 Unauthorized
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldRejectRequestWithoutToken() {
        // Given: No JWT token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var person = Map.of(
            "status", "ACTIVE",
            "userAccount", Map.of(
                "type", "STANDARD",
                "firstName", "John",
                "lastName", "Doe"
            )
        );

        // When: Make request without token
        ResponseEntity<?> response = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            Object.class
        );

        // Then: Request is rejected with 401 Unauthorized
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}


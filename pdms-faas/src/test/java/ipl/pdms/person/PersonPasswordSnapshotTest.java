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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class PersonPasswordSnapshotTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSavePasswordHashAndSaltToDbAndExcludeFromSnapshot() {
        HttpHeaders headers = createHeaders();
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("John")
                        .lastName("Doe")
                        .password("myPassword123")
                        .build())
                .build();

        // Create person
        ResponseEntity<Map<String, Object>> createResponse = restTemplate.exchange(
                getBaseUrl() + "/api/mapper/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Verify password hash and salt are saved in DB
        Map<String, Object> userAccount = jdbcTemplate.queryForMap(
                "SELECT password_hash, password_salt FROM user_account WHERE person_id = ?", personId);
        assertThat(userAccount.get("password_hash")).isNotNull();
        assertThat(userAccount.get("password_salt")).isNotNull();

        // Update to create snapshot
        var update = CreatePersonRequestDto.builder()
                .status("INACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("John")
                        .lastName("Doe")
                        .password("myPassword123")
                        .build())
                .build();
        restTemplate.exchange(
                getBaseUrl() + "/api/mapper/person/" + personId,
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );

        // Verify snapshot doesn't contain password hash or salt
        ResponseEntity<Map<String, Object>> snapshotResponse = restTemplate.exchange(
                getBaseUrl() + "/api/snapshot/person/" + personId + "/latest",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }
        );
        UUID snapshotId = UUID.fromString(snapshotResponse.getBody().get("snapshotId").toString());
        Integer hiddenColumns = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'person_snapshot_user_account'
                  AND column_name IN ('password_hash', 'password_salt')
                """,
                Integer.class
        );
        assertThat(hiddenColumns).isZero();
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));
        return headers;
    }
}



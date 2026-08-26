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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: full nested person (contacts, addresses, consents, preferences) via POST /api/person
 * must succeed — exercises {@code contact_identity} batch insert with {@code ON CONFLICT DO NOTHING}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class PersonCreateFullPayloadApiTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createPerson_postApiPerson_fullNestedPayload_returnsCreated() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("full-payload-person-test"));

        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Maria")
                        .lastName("Silva")
                        .surname("Santos")
                        .password("SecurePassword2025!")
                        .taxNumber("PT987654321")
                        .dateOfBirth(Instant.parse("1988-05-20T00:00:00Z"))
                        .gender("FEMALE")
                        .nationality("Portuguese")
                        .build())
                .contactIdentities(List.of(
                        CreatePersonRequestDto.ContactIdentityDto.builder()
                                .type("EMAIL")
                                .value("maria.silva@company.com")
                                .primary(true)
                                .verificationDate(Instant.parse("2025-04-20T14:30:00Z"))
                                .build(),
                        CreatePersonRequestDto.ContactIdentityDto.builder()
                                .type("PHONE")
                                .value("+351912345678")
                                .primary(false)
                                .verificationDate(Instant.parse("2025-04-21T09:15:00Z"))
                                .build(),
                        CreatePersonRequestDto.ContactIdentityDto.builder()
                                .type("OIDC")
                                .value("maria.silva@google.com")
                                .primary(false)
                                .verificationDate(Instant.parse("2025-04-22T16:45:00Z"))
                                .build()
                ))
                .addresses(List.of(
                        CreatePersonRequestDto.AddressDto.builder()
                                .addressType("HOME")
                                .country("Portugal")
                                .city("Lisbon")
                                .street("Avenida da Liberdade")
                                .streetNumber("245")
                                .flat("5B")
                                .postalCode("1250-096")
                                .companyName(null)
                                .additionalInformation("Near Marquês de Pombal metro station")
                                .verifiedAddress(true)
                                .isPrimary(true)
                                .build(),
                        CreatePersonRequestDto.AddressDto.builder()
                                .addressType("WORK")
                                .country("Portugal")
                                .city("Porto")
                                .street("Rua da Liberdade")
                                .streetNumber("123")
                                .flat(null)
                                .postalCode("4000-100")
                                .companyName("Tech Solutions Lda")
                                .additionalInformation("Office building, 3rd floor")
                                .verifiedAddress(true)
                                .isPrimary(false)
                                .build(),
                        CreatePersonRequestDto.AddressDto.builder()
                                .addressType("BILLING")
                                .country("Portugal")
                                .city("Almada")
                                .street("Rua das Flores")
                                .streetNumber("67")
                                .flat("1A")
                                .postalCode("2800-100")
                                .companyName(null)
                                .additionalInformation("Apartment with balcony")
                                .verifiedAddress(false)
                                .isPrimary(false)
                                .build()
                ))
                .consents(List.of(
                        CreatePersonRequestDto.ConsentDto.builder()
                                .consentType("MARKETING")
                                .granted(true)
                                .grantedAt(Instant.parse("2025-04-23T10:00:00Z"))
                                .expiresAt(Instant.parse("2026-04-23T10:00:00Z"))
                                .consentMethod("WEB")
                                .consentVersion("2.1")
                                .isActive(true)
                                .withdrawalReason(null)
                                .withdrawnAt(null)
                                .build(),
                        CreatePersonRequestDto.ConsentDto.builder()
                                .consentType("ANALYTICS")
                                .granted(true)
                                .grantedAt(Instant.parse("2025-04-23T10:05:00Z"))
                                .expiresAt(Instant.parse("2026-04-23T10:05:00Z"))
                                .consentMethod("WEB")
                                .consentVersion("1.0")
                                .isActive(true)
                                .withdrawalReason(null)
                                .withdrawnAt(null)
                                .build(),
                        CreatePersonRequestDto.ConsentDto.builder()
                                .consentType("THIRD_PARTY_SHARING")
                                .granted(false)
                                .grantedAt(null)
                                .expiresAt(null)
                                .consentMethod(null)
                                .consentVersion("1.5")
                                .isActive(false)
                                .withdrawalReason("Privacy concerns")
                                .withdrawnAt(Instant.parse("2025-04-22T14:30:00Z"))
                                .build()
                ))
                .preferences(CreatePersonRequestDto.PreferencesDto.builder()
                        .language("pt-PT")
                        .nickname("Maria")
                        .build())
                .build();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                getBaseUrl() + "/api/person",
                HttpMethod.POST,
                new HttpEntity<>(person, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("personId");
        assertThat(UUID.fromString(response.getBody().get("personId").toString())).isNotNull();
    }
}

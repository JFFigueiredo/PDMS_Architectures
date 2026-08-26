package ipl.pdms.audit;

import ipl.pdms.IntegrationTestBase;
import ipl.pdms.security.TestJwtTokenGenerator;
import ipl.pdms.security.TestSecurityConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class AuditTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void shouldSaveAuditEventToDatabase() {
        // Given: Create a person (triggers audit)
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

        // When: Create person
        ResponseEntity<Map> response = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            Map.class
        );

        // Then: Verify response is successful
        assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsKey("personId");
        
        UUID personId = UUID.fromString(response.getBody().get("personId").toString());

        // Wait a bit for async audit processing
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // And: Verify audit event was saved to database
        var auditEvents = auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("PERSON", personId);
        assertThat(auditEvents).isNotEmpty();
        assertThat(auditEvents).anyMatch(event -> 
            "PERSON_CREATED".equals(event.getEventType()) && 
            personId.equals(event.getEntityId())
        );
    }

    @Test
    void shouldPublishAuditEventToKafka() throws Exception {
        // Given: Create and update a person (triggers audit and Kafka)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("test-user"));

        var person = Map.of(
            "status", "ACTIVE",
            "userAccount", Map.of(
                "type", "STANDARD",
                "firstName", "Jane",
                "lastName", "Smith"
            )
        );

        // Create person first
        ResponseEntity<Map> createResponse = restTemplate.exchange(
            getBaseUrl() + "/api/mapper/person",
            HttpMethod.POST,
            new HttpEntity<>(person, headers),
            Map.class
        );

        // Verify creation succeeded
        assertThat(createResponse.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody()).containsKey("personId");
        
        UUID personId = UUID.fromString(createResponse.getBody().get("personId").toString());

        // Wait a bit for the create audit event to be processed
        Thread.sleep(500);

        // Set up Kafka consumer before the update to ensure we capture the message
        try (KafkaConsumer<String, Map<String, Object>> consumer = createKafkaConsumer()) {
            consumer.subscribe(Collections.singletonList("pdms.audit.events"));
            
            // Wait for consumer to be assigned partitions
            Thread.sleep(500);
            
            // When: Update person
            var update = Map.of(
                "status", "INACTIVE",
                "userAccount", Map.of(
                    "type", "STANDARD",
                    "firstName", "Jane",
                    "lastName", "Smith"
                )
            );
            ResponseEntity<Map> response = restTemplate.exchange(
                getBaseUrl() + "/api/mapper/person/" + personId,
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                Map.class
            );

            // Then: Verify update succeeded
            assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);

            // Wait a bit for async audit processing
            Thread.sleep(500);

            // And: Verify audit event was saved to database
            var auditEvents = auditEventRepository.findByEntityTypeAndEntityIdOrderByEventTimestampAsc("PERSON", personId);
            assertThat(auditEvents).isNotEmpty();
            assertThat(auditEvents).anyMatch(event -> 
                "PERSON_UPDATED".equals(event.getEventType()) && 
                personId.equals(event.getEntityId())
            );

            // And: Verify audit event was published to Kafka
            // Wait a bit for async Kafka publishing
            Thread.sleep(1000);
            
            // Poll for messages with a timeout
            var records = consumer.poll(Duration.ofSeconds(5));
            
            // Verify we received at least one message for the update
            assertThat(records).isNotEmpty();
            assertThat(records).anyMatch(record -> {
                Map<String, Object> value = record.value();
                return "PERSON_UPDATED".equals(value.get("eventType")) &&
                       personId.toString().equals(value.get("entityId"));
            });
        }
    }

    private KafkaConsumer<String, Map<String, Object>> createKafkaConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getKafkaContainer().getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");
        
        return new KafkaConsumer<>(props);
    }
}


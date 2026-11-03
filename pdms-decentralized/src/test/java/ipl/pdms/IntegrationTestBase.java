package ipl.pdms;

import ipl.pdms.configuration.EnableTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for integration tests using Testcontainers.
 * Ensures each test class gets a fresh Spring context so container ports match.
 */
@EnableTestContainers
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private ipl.pdms.outbox.runtime.RuntimeOutboxControl runtimePermission;

    @BeforeEach
    void enableIsolatedRuntimePermission() {
        if (runtimePermission.status().enabled()) return;
        var state = runtimePermission.status();
        var change = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("expectedVersion", state.snapshot().version()).put("expectedInstanceId", state.instanceId())
                .put("requestId", java.util.UUID.randomUUID().toString()).put("reason", "Isolated integration fixture")
                .put("enabled", true);
        runtimePermission.change(change);
    }

    private static final String BASE_URL = "http://localhost";

    @LocalServerPort
    protected int port;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("pdms_test")
            .withUsername("pdms_user")
            .withPassword("pdms_password")
            .withLabel("testcontainers.pdms.postgres", "true")
            .withReuse(true)
            .withStartupTimeout(java.time.Duration.ofMinutes(2))
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                    .forLogMessage(".*database system is ready to accept connections.*", 2)
                    .withStartupTimeout(java.time.Duration.ofMinutes(2)));

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withKraft()
            .withLabel("testcontainers.pdms.kafka", "true")
            .withReuse(false) // Disable reuse to avoid container state issues
            .withStartupTimeout(java.time.Duration.ofMinutes(3));
            // Let KafkaContainer use its default wait strategy which handles KRaft mode properly

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Wait for containers to be running and ready
        // Testcontainers should handle this automatically, but we verify
        int maxWaitTime = 120000; // 2 minutes
        long startTime = System.currentTimeMillis();
        
        while (!postgres.isRunning() && (System.currentTimeMillis() - startTime) < maxWaitTime) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (!postgres.isRunning()) {
            throw new IllegalStateException("PostgreSQL container failed to start within " + maxWaitTime + "ms");
        }
        
        while (!kafka.isRunning() && (System.currentTimeMillis() - startTime) < maxWaitTime) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        if (!kafka.isRunning()) {
            throw new IllegalStateException("Kafka container failed to start within " + maxWaitTime + "ms");
        }
        
        // Wait a bit more for containers to be fully ready and accepting connections
        // Kafka especially needs time to fully initialize
        try {
            Thread.sleep(10000); // Wait 10 seconds for containers to be fully ready
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Verify Kafka is actually accessible
        String kafkaBootstrapServers = kafka.getBootstrapServers();
        if (kafkaBootstrapServers == null || kafkaBootstrapServers.isEmpty()) {
            throw new IllegalStateException("Kafka bootstrap servers not available");
        }
        
        // Set database properties explicitly
        // With container reuse enabled, ports will remain consistent across test runs
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        
        // Set Flyway properties to use the same connection
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        
        // Set Kafka properties
        // With container reuse enabled, ports will remain consistent across test runs
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeEach
    void setUp() {
        // Verify containers are running with retry logic
        // Sometimes containers can be in a transitional state
        int maxRetries = 10;
        int retryDelayMs = 500;
        
        boolean postgresRunning = false;
        for (int i = 0; i < maxRetries; i++) {
            if (postgres.isRunning()) {
                postgresRunning = true;
                break;
            }
            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(postgresRunning)
                .withFailMessage("PostgreSQL container is not running after %d retries. Container state: %s", 
                        maxRetries, postgres.getContainerId())
                .isTrue();
        
        boolean kafkaRunning = false;
        String kafkaBootstrapServers = null;
        for (int i = 0; i < maxRetries; i++) {
            if (kafka.isRunning()) {
                try {
                    kafkaBootstrapServers = kafka.getBootstrapServers();
                    if (kafkaBootstrapServers != null && !kafkaBootstrapServers.isEmpty()) {
                        kafkaRunning = true;
                        break;
                    }
                } catch (Exception e) {
                    // Container might be running but not ready yet
                }
            }
            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(kafkaRunning)
                .withFailMessage("Kafka container is not running or not ready after %d retries. " +
                        "Container state: %s, Bootstrap servers: %s", 
                        maxRetries, kafka.getContainerId(), kafkaBootstrapServers)
                .isTrue();
        
        System.out.println("✅ PostgreSQL container running on: " + postgres.getJdbcUrl());
        System.out.println("✅ Kafka container running on: " + kafka.getBootstrapServers());
    }

    /**
     * Builds a test server base url
     *
     * @return concatenation of base url and test server port
     */
    protected final String getBaseUrl() {
        return BASE_URL + ":" + port;
    }

    /**
     * Get the PostgreSQL container for direct database access if needed
     */
    protected PostgreSQLContainer<?> getPostgresContainer() {
        return postgres;
    }

    /**
     * Get the Kafka container for direct messaging access if needed
     */
    protected KafkaContainer getKafkaContainer() {
        return kafka;
    }
}

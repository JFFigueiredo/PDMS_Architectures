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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates consent is anchored on Besu: {@code onchain_consent_outbox} CONFIRMED, receipt OK,
 * and {@code eth_getTransactionByHash} returns the same mined tx targeting ConsentAnchor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@Import(TestSecurityConfiguration.class)
class BesuOnchainLedgerIntegrationTest extends IntegrationTestBase {

    @Container
    static GenericContainer<?> besu = new GenericContainer<>(DockerImageName.parse("hyperledger/besu:24.3"))
            .withCommand(
                    "--data-path=/tmp/besu-data",
                    "--network=dev",
                    "--rpc-http-enabled=true",
                    "--rpc-http-host=0.0.0.0",
                    "--rpc-http-port=8545",
                    "--rpc-http-api=ETH,NET,WEB3,MINER,ADMIN,TXPOOL",
                    "--host-allowlist=*",
                    "--miner-enabled=true",
                    /* Coinbase = account for test private key (Besu dev genesis funded address) */
                    "--miner-coinbase=0xfe3b557e8fb62b89f4916b721be55ceb828dbd73"
            )
            .withExposedPorts(8545)
            .withStartupTimeout(Duration.ofMinutes(4))
            .waitingFor(
                    Wait.forLogMessage(".*JSON-RPC service started and listening.*8545.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(3))
            );

    @DynamicPropertySource
    static void registerBesuBlockchain(DynamicPropertyRegistry registry) {
        registry.add("pdms.blockchain.enabled", () -> "true");
        registry.add("pdms.blockchain.rpc-url", () -> "http://127.0.0.1:" + besu.getMappedPort(8545));
        registry.add("pdms.blockchain.private-key", () -> "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63");
        /* Besu 24.x --network=dev uses chain id 2018 (see container startup log). */
        registry.add("pdms.blockchain.chain-id", () -> "2018");
        registry.add("pdms.blockchain.scheduled-fixed-delay-ms", () -> "200");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Web3j web3j;

    @Test
    void createConsent_eventuallyConfirmedOnBesuLedger() throws Exception {
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
        UUID consentId = createResponse.getBody().id();

        outboxService.processOutboxEvents();

        String txHash = waitForOnchainTxHash(consentId, Duration.ofMinutes(3));

        TransactionReceipt receipt = web3j.ethGetTransactionReceipt(txHash).send()
                .getTransactionReceipt()
                .orElseThrow(() -> new AssertionError("missing receipt for " + txHash));

        assertThat(receipt.isStatusOK()).isTrue();
        assertThat(receipt.getBlockNumber()).isNotNull();
        assertThat(receipt.getGasUsed()).isGreaterThan(BigInteger.ZERO);

        String contract = jdbcTemplate.queryForObject(
                "SELECT contract_address FROM blockchain_integration_state WHERE singleton = 1",
                String.class
        );
        assertThat(contract).isNotBlank();
        assertThat(receipt.getTo()).isEqualToIgnoringCase(contract);

        var ledgerTx = web3j.ethGetTransactionByHash(txHash).send().getTransaction();
        assertThat(ledgerTx).as("Besu must expose the anchor tx on the ledger (eth_getTransactionByHash)").isPresent();
        assertThat(ledgerTx.get().getBlockNumber()).isEqualTo(receipt.getBlockNumber());
        assertThat(ledgerTx.get().getTo()).isEqualToIgnoringCase(contract);
    }

    private String waitForOnchainTxHash(UUID consentId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String tx = jdbcTemplate.query(
                    """
                    SELECT tx_hash FROM onchain_consent_outbox
                    WHERE consent_id = ? AND status = 'CONFIRMED' AND tx_hash IS NOT NULL
                    ORDER BY created_at DESC LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getString(1) : null,
                    consentId
            );
            if (tx != null && !tx.isBlank()) {
                return tx;
            }
            Thread.sleep(400);
        }
        throw new AssertionError("onchain_consent_outbox not CONFIRMED for consent " + consentId + " within " + timeout);
    }

    private UUID createPerson(HttpHeaders headers) {
        var person = CreatePersonRequestDto.builder()
                .status("ACTIVE")
                .userAccount(CreatePersonRequestDto.UserAccountDto.builder()
                        .type("STANDARD")
                        .firstName("Besu")
                        .lastName("Ledger")
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
        headers.setBearerAuth(TestJwtTokenGenerator.generateToken("besu-ledger-test"));
        return headers;
    }
}

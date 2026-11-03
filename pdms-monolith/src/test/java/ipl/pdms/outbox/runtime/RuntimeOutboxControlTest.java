package ipl.pdms.outbox.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ipl.pdms.outbox.OutboxRepository;
import ipl.pdms.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local flags plus real PostgreSQL outbox transactions; no database policy schema. */
@Testcontainers(disabledWithoutDocker = true)
class RuntimeOutboxControlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");
    final ObjectMapper mapper = new ObjectMapper();
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    RuntimeOutboxControl monolith;
    OutboxRepository repository;

    @BeforeEach
    void reset() {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS outbox");
        jdbc.execute("""
                CREATE TABLE outbox (id UUID PRIMARY KEY, aggregate_type TEXT, aggregate_id TEXT,
                event_type TEXT, payload JSONB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                processed_at TIMESTAMP, processing_status TEXT DEFAULT 'PENDING')
                """);
        monolith = control("monolith", false, 5000);
        repository = new OutboxRepository(jdbc, mapper);
    }

    RuntimeOutboxControl control(String architecture, boolean blockchain, int timeout) {
        return new RuntimeOutboxControl(new DataSourceTransactionManager(source), architecture, blockchain, timeout, 25);
    }

    ObjectNode request(String fields) throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree(fields);
        request.put("expectedVersion", monolith.status().snapshot().version());
        request.put("expectedInstanceId", monolith.status().instanceId());
        if (!request.has("enabled")) request.put("enabled", monolith.status().enabled());
        request.put("requestId", UUID.randomUUID().toString());
        request.put("reason", "runtime control test");
        return request;
    }

    void activate(String architecture) throws Exception {
        monolith.change(request("{\"enabled\":" + architecture.equals("monolith") + "}"));
    }

    void set(RuntimeOutboxControl process, boolean enabled) {
        ObjectNode request = mapper.createObjectNode().put("expectedVersion", process.status().snapshot().version())
                .put("expectedInstanceId", process.status().instanceId()).put("requestId", UUID.randomUUID().toString())
                .put("reason", "Local process fixture").put("enabled", enabled);
        process.change(request);
    }

    UUID event(String type, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload,
                  processing_status, created_at, processed_at)
                VALUES (?, 'PERSON', 'person-1', ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP - INTERVAL '10 minutes',
                  CURRENT_TIMESTAMP - INTERVAL '10 minutes')
                """, id, type, state);
        return id;
    }

    String state(UUID id) {
        return jdbc.queryForObject("SELECT processing_status FROM outbox WHERE id = ?", String.class, id);
    }

    @Test void bootstrapIsPausedAndNewInstancesDoNotRestoreFlags() throws Exception {
        assertThat(monolith.status().enabled()).isFalse();
        monolith.withSharedEvents(types -> fail("Disabled ordinary workers must not run"));
        activate("monolith");
        assertThat(monolith.status().effectiveEventTypes()).contains("PERSON_CREATED");
        var restarted = control("monolith", false, 5000);
        assertThat(restarted.status().enabled()).isFalse();
        assertThat(restarted.status().instanceId()).isNotEqualTo(monolith.status().instanceId());
    }

    @Test void explicitPerProcessUpdatesSelectOneOrdinaryArchitecture() {
        List<RuntimeOutboxControl> processes = List.of(control("monolith", false, 5000), control("faas", false, 5000),
                control("microservices", false, 5000), control("microservices-async", false, 5000), control("decentralized", true, 5000));
        for (RuntimeOutboxControl owner : processes) {
            processes.forEach(process -> set(process, false));
            set(owner, true);
            List<String> ran = new ArrayList<>();
            for (var process : processes) process.withSharedEvents(types -> {
                if (types.stream().anyMatch(RuntimeOutboxControl.ORDINARY_TYPES::contains)) ran.add(process.status().architecture());
            });
            assertThat(ran).containsExactly(owner.status().architecture());
        }
    }

    @Test void filtersBeforeClaimingAndProtectsLedgerRows() throws Exception {
        UUID allowed = event("PERSON_CREATED", "PENDING");
        UUID ledger = event(RuntimeOutboxControl.LEDGER_TYPE, "PENDING");
        activate("monolith");
        monolith.withSharedEvents(types -> {
            var claimed = repository.findPending(10, types);
            assertThat(claimed).hasSize(1);
            assertThat(claimed.getFirst().get("id")).isEqualTo(allowed);
            repository.deleteById(allowed);
        });
        assertThat(state(ledger)).isEqualTo("PENDING");
        // The dedicated ledger lane runs even while monolith remains ordinary owner.
        control("decentralized", true, 5000).withSharedEvents(types -> {
            assertThat(types).containsExactly(RuntimeOutboxControl.LEDGER_TYPE);
            assertThat(repository.findPending(10, types).getFirst().get("id")).isEqualTo(ledger);
        });
    }

    @Test void productionServiceHonoursPauseOwnershipAndFiltering() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        var service = new OutboxService(repository, monolith, kafka);
        ReflectionTestUtils.setField(service, "batchSize", 10);
        ReflectionTestUtils.setField(service, "processingTimeoutMinutes", 5);
        UUID person = event("PERSON_CREATED", "PENDING");
        UUID anchor = event(RuntimeOutboxControl.LEDGER_TYPE, "PENDING");
        service.processOutboxEvents();
        verifyNoInteractions(kafka);
        assertThat(state(person)).isEqualTo("PENDING");
        activate("faas");
        service.processOutboxEvents();
        verifyNoInteractions(kafka);
        activate("monolith");
        service.processOutboxEvents();
        verify(kafka, times(1)).send(eq("pdms.person.change"), eq("person-1"), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, person)).isZero();
        assertThat(state(anchor)).isEqualTo("PENDING");
        UUID failed = event("PERSON_UPDATED", "FAILED");
        UUID stuck = event("PERSON_UPDATED", "PROCESSING");
        activate("faas");
        service.retryFailedEvents();
        service.resetStuckProcessingEvents();
        assertThat(state(failed)).isEqualTo("FAILED");
        assertThat(state(stuck)).isEqualTo("PROCESSING");
    }

    @Test void retryAndResetRespectTheSameAllowedTypes() throws Exception {
        UUID ordinaryFailed = event("PERSON_CREATED", "FAILED");
        UUID ledgerFailed = event(RuntimeOutboxControl.LEDGER_TYPE, "FAILED");
        UUID ordinaryStuck = event("PERSON_UPDATED", "PROCESSING");
        UUID ledgerStuck = event(RuntimeOutboxControl.LEDGER_TYPE, "PROCESSING");
        activate("monolith");
        monolith.withSharedEvents(types -> {
            assertThat(repository.retryFailedEvents(100, types)).isEqualTo(1);
            assertThat(repository.resetStuckProcessingEvents(5, types)).isEqualTo(1);
        });
        assertThat(state(ordinaryFailed)).isEqualTo("PENDING");
        assertThat(state(ordinaryStuck)).isEqualTo("PENDING");
        assertThat(state(ledgerFailed)).isEqualTo("FAILED");
        assertThat(state(ledgerStuck)).isEqualTo("PROCESSING");
    }

    @Test void removedTypesAndEmptyListsRetainWork() throws Exception {
        activate("monolith");
        monolith.change(request("{\"enabled\":true,\"allowedEventTypes\":[\"PERSON_UPDATED\"]}"));
        UUID retained = event("PERSON_CREATED", "PENDING");
        monolith.withSharedEvents(types -> assertThat(repository.findPending(10, types)).isEmpty());
        assertThat(state(retained)).isEqualTo("PENDING");
        monolith.change(request("{\"enabled\":true,\"allowedEventTypes\":[]}"));
        monolith.withSharedEvents(types -> fail("Empty ordinary allowlist must not run"));
    }

    @Test void onchainIgnoresTheOrdinaryFlagAndRequiresStartupCapability() {
        var decentralized = control("decentralized", true, 5000);
        AtomicInteger calls = new AtomicInteger();
        decentralized.withOnchainEvents(types -> { assertThat(types).isEqualTo(RuntimeOutboxControl.ONCHAIN_TYPES); calls.incrementAndGet(); });
        assertThat(calls).hasValue(1);
        set(decentralized, true); set(decentralized, false);
        decentralized.withOnchainEvents(types -> calls.incrementAndGet());
        assertThat(calls).hasValue(2);
        control("decentralized", false, 5000).withOnchainEvents(types -> fail("Startup capability is absent"));
        control("monolith", true, 5000).withOnchainEvents(types -> fail("Other architectures cannot anchor"));
    }

    @Test void decentralizedUsesOneOrdinaryFlagAndKeepsLedgerEnabled() {
        var decentralized = control("decentralized", true, 5000);
        assertThat(decentralized.status().effectiveEventTypes()).containsExactly(RuntimeOutboxControl.LEDGER_TYPE);
        set(decentralized, true);
        assertThat(decentralized.status().effectiveEventTypes()).containsAll(RuntimeOutboxControl.ORDINARY_TYPES).contains(RuntimeOutboxControl.LEDGER_TYPE);
        set(decentralized, false);
        assertThat(decentralized.status().effectiveEventTypes()).containsExactly(RuntimeOutboxControl.LEDGER_TYPE);
        assertThat(decentralized.status().effectiveOnchainAnchorTypes()).isEqualTo(RuntimeOutboxControl.ONCHAIN_TYPES);
    }

    @Test void ledgerOnlyPollerLeavesOrdinaryPendingFailedAndStuckRowsUntouched() throws Exception {
        UUID ordinary = event("PERSON_CREATED", "PENDING");
        UUID ordinaryFailed = event("PERSON_UPDATED", "FAILED");
        UUID ordinaryStuck = event("PERSON_DELETED", "PROCESSING");
        UUID anchor = event(RuntimeOutboxControl.LEDGER_TYPE, "PENDING");
        UUID failedAnchor = event(RuntimeOutboxControl.LEDGER_TYPE, "FAILED");
        UUID stuckAnchor = event(RuntimeOutboxControl.LEDGER_TYPE, "PROCESSING");
        control("decentralized", true, 5000).withSharedEvents(types -> {
            var rows = repository.findPending(10, types);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("id")).isEqualTo(anchor);
            repository.deleteById(anchor);
            assertThat(repository.retryFailedEvents(10, types)).isEqualTo(1);
            assertThat(repository.resetStuckProcessingEvents(5, types)).isEqualTo(1);
        });
        assertThat(state(ordinary)).isEqualTo("PENDING");
        assertThat(state(ordinaryFailed)).isEqualTo("FAILED");
        assertThat(state(ordinaryStuck)).isEqualTo("PROCESSING");
        assertThat(state(failedAnchor)).isEqualTo("PENDING");
        assertThat(state(stuckAnchor)).isEqualTo("PENDING");
    }

    @Test void ordinaryChangesDoNotRewriteAnotherProcessOrItsLedger() throws Exception {
        var decentralized = control("decentralized", true, 5000);
        activate("monolith");
        assertThat(decentralized.status().enabled()).isFalse();
        assertThat(decentralized.status().snapshot().version()).isZero();
        assertThat(decentralized.status().effectiveEventTypes()).containsExactly(RuntimeOutboxControl.LEDGER_TYPE);
        monolith.change(request("{\"enabled\":false}"));
        assertThat(decentralized.status().effectiveOnchainAnchorTypes()).isEqualTo(RuntimeOutboxControl.ONCHAIN_TYPES);
    }

    @Test void validatesBooleansTypesAndUnknownFieldsBeforeChangingState() throws Exception {
        for (String invalid : List.of("{\"allowedEventTypes\":[\"CONSENT_LEDGER_ANCHOR\"]}",
                "{\"allowedEventTypes\":[\"NOT_A_TYPE\"]}", "{\"enabled\":\"false\"}",
                "{\"enabled\":false,\"enabledd\":true}", "{\"ledgerProcessingEnabled\":false}")) {
            assertThatThrownBy(() -> monolith.change(request(invalid))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(monolith.status().snapshot().version()).isZero();
        }
    }

    @Test void rejectsStaleVersionsAndReplaysOnlyTheIdenticalRequest() throws Exception {
        ObjectNode first = request("{\"enabled\":true}");
        var result = monolith.change(first);
        assertThat(monolith.change(first)).isEqualTo(result);
        assertThat(monolith.status().snapshot().version()).isEqualTo(1);
        ObjectNode stale = request("{\"enabled\":false}"); stale.put("expectedVersion", 0);
        assertThatThrownBy(() -> monolith.change(stale)).hasMessageContaining("Stale");
        first.put("enabled", false);
        assertThatThrownBy(() -> monolith.change(first)).hasMessageContaining("requestId");
    }

    @Test void disableWaitsUntilTheLocalOrdinaryBatchFinishes() throws Exception {
        activate("monolith");
        ObjectNode next = request("{\"enabled\":false}");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var worker = executor.submit(() -> monolith.withSharedEvents(types -> { entered.countDown(); await(release); }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var change = executor.submit(() -> monolith.change(next));
                assertThatThrownBy(() -> change.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(monolith.status().enabled()).isFalse();
                release.countDown(); worker.get(5, TimeUnit.SECONDS);
                assertThat(change.get(5, TimeUnit.SECONDS).policy().enabled()).isFalse();
                monolith.withSharedEvents(types -> fail("Disabled worker still ran"));
            } finally { release.countDown(); }
        }
    }

    @Test void drainTimeoutLeavesTheProcessDisabledUntilReconciled() throws Exception {
        monolith = control("monolith", false, 100); activate("monolith");
        ObjectNode next = request("{\"enabled\":false}");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var worker = executor.submit(() -> monolith.withSharedEvents(types -> { entered.countDown(); await(release); }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> monolith.change(next)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
                assertThat(monolith.status().enabled()).isFalse();
                assertThat(monolith.status().drained()).isFalse();
            } finally { release.countDown(); }
            worker.get(5, TimeUnit.SECONDS);
        }
        set(monolith, false); assertThat(monolith.status().drained()).isTrue();
    }

    @Test void failedWorkerRollsBackClaimsAndReleasesControlLock() throws Exception {
        activate("monolith");
        UUID id = event("PERSON_CREATED", "PENDING");
        assertThatThrownBy(() -> monolith.withSharedEvents(types -> {
            repository.findPending(10, types);
            throw new IllegalStateException("worker failed");
        })).hasMessageContaining("worker failed");
        assertThat(state(id)).isEqualTo("PENDING");
        activate("faas");
    }

    @Test void disabledOrdinaryPermissionDoesNotOpenADatabaseTransaction() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var local = new RuntimeOutboxControl(manager, "monolith", false, 5000, 25);
        local.withSharedEvents(types -> fail("Disabled worker must not run"));
        verifyNoInteractions(manager);
        assertThat(local.enterBusiness()).isFalse();
    }

    @Test void adminEndpointRequiresSeparateTokenAndAcceptsRuntimePatch() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(new OutboxControlController(monolith, "test-secret")).build();
        http.perform(get("/actuator/outbox-control")).andExpect(status().isUnauthorized());
        http.perform(get("/actuator/outbox-control").header("X-Outbox-Admin-Token", "wrong")).andExpect(status().isUnauthorized());
        http.perform(get("/actuator/outbox-control").header("X-Outbox-Admin-Token", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        http.perform(patch("/actuator/outbox-control").header("X-Outbox-Admin-Token", "test-secret")
                .contentType(MediaType.APPLICATION_JSON).content(request("{\"enabled\":true}").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snapshot.policy.enabled").value(true));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}

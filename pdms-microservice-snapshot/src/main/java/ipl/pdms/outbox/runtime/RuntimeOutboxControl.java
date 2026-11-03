package ipl.pdms.outbox.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/** Process-local permission; the gateway coordinates all process acknowledgements. */
@Service
public class RuntimeOutboxControl {
    public static final Set<String> ORDINARY_TYPES = Set.of("PERSON_CREATED", "PERSON_UPDATED", "PERSON_DELETED", "PERSON_SNAPSHOT_CREATED");
    public static final String LEDGER_TYPE = "CONSENT_LEDGER_ANCHOR";
    public static final Set<String> ONCHAIN_TYPES = Set.of("CONSENT_CREATED", "CONSENT_UPDATED", "CONSENT_WITHDRAWN", "CONSENT_DELETED");
    private static final Set<String> ARCHITECTURES = Set.of("monolith", "microservices", "microservices-async", "faas", "decentralized");
    private static final Set<String> FIELDS = Set.of("expectedVersion", "expectedInstanceId", "requestId",
            "reason", "enabled", "allowedEventTypes");
    private final TransactionTemplate transactions;
    private final String architecture;
    private final boolean blockchainAvailable;
    private final int changeLockTimeoutMs;
    private final int maxBatchSize;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicReference<Snapshot> current;
    private final ReentrantReadWriteLock ordinary = new ReentrantReadWriteLock(true);
    private final Object admission = new Object();
    private int businessRequests;
    private volatile boolean draining;
    private final Map<String, Replay> replays = new LinkedHashMap<>();
    private record Replay(String body, Snapshot result) {}

    public RuntimeOutboxControl(PlatformTransactionManager manager,
            @Value("${pdms.architecture-id}") String architecture,
            @Value("${pdms.blockchain.enabled:false}") boolean blockchainAvailable,
            @Value("${pdms.outbox.change-lock-timeout-ms:5000}") int changeLockTimeoutMs,
            @Value("${pdms.outbox.max-batch-size:25}") int maxBatchSize) {
        if (!ARCHITECTURES.contains(architecture) || changeLockTimeoutMs < 1 || maxBatchSize < 1) {
            throw new IllegalArgumentException("Invalid runtime outbox configuration");
        }
        this.architecture = architecture;
        this.blockchainAvailable = blockchainAvailable && "decentralized".equals(architecture);
        this.changeLockTimeoutMs = changeLockTimeoutMs;
        this.maxBatchSize = maxBatchSize;
        current = new AtomicReference<>(new Snapshot(0, new Policy(false, ORDINARY_TYPES)));
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Policy(boolean enabled, Set<String> allowedEventTypes) {
        public Policy { allowedEventTypes = Set.copyOf(allowedEventTypes); }
    }
    public record Snapshot(long version, Policy policy) {}
    public record Status(String architecture, boolean blockchainAvailable, Snapshot snapshot,
                         Set<String> effectiveEventTypes, Set<String> effectiveOnchainAnchorTypes,
                         String instanceId, boolean enabled, int businessRequests, boolean drained) {}

    public Status status() {
        synchronized (admission) {
            Snapshot snapshot = current.get();
            return new Status(architecture, blockchainAvailable, snapshot,
                    effectiveTypes(snapshot),
                    blockchainAvailable ? ONCHAIN_TYPES : Set.of(), instanceId,
                    snapshot.policy().enabled(), businessRequests,
                    !draining && businessRequests == 0 && ordinary.getReadLockCount() == 0);
        }
    }

    private Set<String> effectiveTypes(Snapshot snapshot) {
        Set<String> types = new java.util.HashSet<>();
        if (snapshot.policy().enabled()) types.addAll(snapshot.policy().allowedEventTypes());
        if ("decentralized".equals(architecture)) types.add(LEDGER_TYPE);
        return Set.copyOf(types);
    }

    public boolean enterBusiness() {
        synchronized (admission) {
            if (!current.get().policy().enabled() || draining) return false;
            businessRequests++;
            return true;
        }
    }

    /** Retain already-admitted async work until its outbound exchange completes. */
    public void retainBusinessWork() { synchronized (admission) { businessRequests++; } }

    public void exitBusiness() {
        synchronized (admission) { businessRequests--; admission.notifyAll(); }
    }

    public int batchLimit(int configured) { return Math.min(configured, maxBatchSize); }

    public void withSharedEvents(Consumer<Set<String>> work) {
        if (current.get().policy().enabled() && ordinary.readLock().tryLock()) {
            try {
                Set<String> types = new java.util.HashSet<>(effectiveTypes(current.get()));
                types.retainAll(ORDINARY_TYPES);
                if (!types.isEmpty()) transactions.executeWithoutResult(tx -> work.accept(Set.copyOf(types)));
            } finally { ordinary.readLock().unlock(); }
        }
        // A separate transaction/guard: ordinary disable does not wait for or stop ledger work.
        if ("decentralized".equals(architecture)) {
            transactions.executeWithoutResult(tx -> work.accept(Set.of(LEDGER_TYPE)));
        }
    }

    public void withOnchainEvents(Consumer<Set<String>> work) {
        if (blockchainAvailable) {
            transactions.executeWithoutResult(tx -> work.accept(ONCHAIN_TYPES));
        }
    }

    /** Disabling closes admission immediately, then acknowledges only after ordinary work drains. */
    public synchronized Snapshot change(ObjectNode request) {
        require(request != null && request.properties().stream().allMatch(e -> FIELDS.contains(e.getKey())), "Unknown request field");
        if (!request.path("expectedInstanceId").isTextual()
                || !instanceId.equals(request.path("expectedInstanceId").textValue()))
            throw conflict("Process restarted; read its current instanceId");
        require(request.path("expectedVersion").isIntegralNumber() && request.path("expectedVersion").canConvertToLong()
                && request.path("expectedVersion").longValue() >= 0, "expectedVersion must be a nonnegative integer");
        require(request.path("enabled").isBoolean(), "enabled must be boolean");
        require(request.path("reason").isTextual() && !request.path("reason").textValue().isBlank()
                && request.path("reason").textValue().length() <= 300, "reason must contain 1 to 300 characters");
        String id = request.path("requestId").asText();
        try { UUID.fromString(id); } catch (IllegalArgumentException invalid) { throw badRequest("requestId must be a UUID"); }
        Replay previous = replays.get(id);
        if (previous != null) {
            if (!previous.body().equals(request.toString())) throw conflict("requestId reused with different content");
            return previous.result();
        }
        if (replays.size() >= 10000) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Control history full; reconcile before restart");
        Snapshot before = current.get();
        if (before.version() != request.path("expectedVersion").longValue()) throw conflict("Stale expectedVersion");
        Set<String> types = before.policy().allowedEventTypes();
        if (request.has("allowedEventTypes")) {
            require(request.path("allowedEventTypes").isArray() && request.path("allowedEventTypes").size() <= 4,
                    "allowedEventTypes must be an array of at most four types");
            Set<String> allowed = new java.util.HashSet<>();
            request.path("allowedEventTypes").forEach(type -> {
                require(type.isTextual() && ORDINARY_TYPES.contains(type.textValue()), "Unsupported ordinary event type");
                allowed.add(type.textValue());
            });
            types = Set.copyOf(allowed);
        }
        long nextVersion = Math.addExact(before.version(), 1);
        boolean enabled = request.path("enabled").booleanValue();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(changeLockTimeoutMs);
        synchronized (admission) {
            draining = true;
            current.set(new Snapshot(nextVersion, new Policy(false, types)));
        }
        boolean locked = false;
        try {
            synchronized (admission) {
                while (businessRequests != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw conflict("Business requests still draining; process remains disabled");
                    TimeUnit.NANOSECONDS.timedWait(admission, remaining);
                }
            }
            long remaining = deadline - System.nanoTime();
            locked = ordinary.writeLock().tryLock(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            if (!locked) throw conflict("Ordinary batches still draining; process remains disabled");
            Snapshot after = new Snapshot(nextVersion, new Policy(enabled, types));
            synchronized (admission) { current.set(after); draining = false; }
            replays.put(id, new Replay(request.toString(), after));
            return after;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw conflict("Drain interrupted; process remains disabled");
        } finally {
            if (locked) ordinary.writeLock().unlock();
            // On failure draining stays true. Only a fresh fenced command can resume this process.
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw badRequest(message); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}

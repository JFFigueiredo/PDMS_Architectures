package ipl.pdms.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class GdprMetrics {

    private final MeterRegistry registry;
    private final Counter consentGrant;
    private final Counter consentRevoke;
    private final Counter consentDelete;
    private final Counter privacyPolicyAcceptance;
    private final Counter dataExportRequest;
    private final Counter dataDeletionRequest;
    private final Timer dsarProcessing;
    private final Timer dataDeletionProcessing;
    private final AtomicInteger dsarRequestsInProgress;
    private final AtomicInteger dataDeletionRequestsInProgress;

    public GdprMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.consentGrant = Counter.builder("pdms.consent.grant")
                .description("Number of consents granted")
                .register(registry);
        this.consentRevoke = Counter.builder("pdms.consent.revoke")
                .description("Number of consents revoked or withdrawn")
                .register(registry);
        this.consentDelete = Counter.builder("pdms.consent.delete")
                .description("Number of consents deleted")
                .register(registry);
        this.privacyPolicyAcceptance = Counter.builder("pdms.privacy.policy.acceptance")
                .description("Number of privacy policy consents granted")
                .register(registry);
        this.dataExportRequest = Counter.builder("pdms.data.export.request")
                .description("Number of data export (DSAR) requests fulfilled")
                .register(registry);
        this.dataDeletionRequest = Counter.builder("pdms.data.deletion.request")
                .description("Number of data deletion (right to erasure) requests")
                .register(registry);
        this.dsarProcessing = Timer.builder("pdms.dsar.processing")
                .description("Latency of Data Subject Access Request processing")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.dataDeletionProcessing = Timer.builder("pdms.data.deletion.processing")
                .description("Latency of data deletion request processing")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.dsarRequestsInProgress = registry.gauge("pdms.dsar.requests.in.progress",
                new AtomicInteger(0));
        this.dataDeletionRequestsInProgress = registry.gauge("pdms.data.deletion.requests.in.progress",
                new AtomicInteger(0));
    }

    public void consentGranted(String consentType) {
        consentGrant.increment();
        if ("PRIVACY_POLICY".equalsIgnoreCase(consentType)) {
            privacyPolicyAcceptance.increment();
        }
    }

    public void consentRevoked() {
        consentRevoke.increment();
    }

    public void consentDeleted() {
        consentDelete.increment();
    }

    public void dataExportRequested() {
        dataExportRequest.increment();
    }

    public void dataDeletionRequested() {
        dataDeletionRequest.increment();
    }

    public Timer.Sample startDsarTimer() {
        dsarRequestsInProgress.incrementAndGet();
        return Timer.start(registry);
    }

    public void stopDsarTimer(Timer.Sample sample) {
        sample.stop(dsarProcessing);
        dsarRequestsInProgress.updateAndGet(current -> Math.max(0, current - 1));
    }

    public Timer.Sample startDataDeletionTimer() {
        dataDeletionRequestsInProgress.incrementAndGet();
        return Timer.start(registry);
    }

    public void stopDataDeletionTimer(Timer.Sample sample) {
        sample.stop(dataDeletionProcessing);
        dataDeletionRequestsInProgress.updateAndGet(current -> Math.max(0, current - 1));
    }
}

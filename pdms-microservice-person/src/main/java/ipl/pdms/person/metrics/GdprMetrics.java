package ipl.pdms.person.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class GdprMetrics {

    private final MeterRegistry registry;
    private final Counter dataDeletionRequest;
    private final Timer dataDeletionProcessing;

    public GdprMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.dataDeletionRequest = Counter.builder("pdms.data.deletion.request")
                .description("Number of data deletion (right to erasure) requests")
                .register(registry);
        this.dataDeletionProcessing = Timer.builder("pdms.data.deletion.processing")
                .description("Latency of data deletion request processing")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void dataDeletionRequested() {
        dataDeletionRequest.increment();
    }

    public Timer.Sample startDataDeletionTimer() {
        return Timer.start(registry);
    }

    public void stopDataDeletionTimer(Timer.Sample sample) {
        sample.stop(dataDeletionProcessing);
    }
}

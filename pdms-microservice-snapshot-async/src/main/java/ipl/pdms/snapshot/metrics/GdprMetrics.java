package ipl.pdms.snapshot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class GdprMetrics {

    private final MeterRegistry registry;
    private final Counter dataExportRequest;
    private final Timer dsarProcessing;

    public GdprMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.dataExportRequest = Counter.builder("pdms.data.export.request")
                .description("Number of data export (DSAR) requests fulfilled")
                .register(registry);
        this.dsarProcessing = Timer.builder("pdms.dsar.processing")
                .description("Latency of Data Subject Access Request processing")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void dataExportRequested() {
        dataExportRequest.increment();
    }

    public Timer.Sample startDsarTimer() {
        return Timer.start(registry);
    }

    public void stopDsarTimer(Timer.Sample sample) {
        sample.stop(dsarProcessing);
    }
}

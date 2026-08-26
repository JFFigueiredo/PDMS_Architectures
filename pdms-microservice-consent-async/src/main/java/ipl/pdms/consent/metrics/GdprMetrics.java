package ipl.pdms.consent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class GdprMetrics {

    private final Counter consentGrant;
    private final Counter consentRevoke;
    private final Counter consentDelete;
    private final Counter privacyPolicyAcceptance;

    public GdprMetrics(MeterRegistry registry) {
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
}

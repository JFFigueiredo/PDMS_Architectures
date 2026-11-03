package ipl.pdms.consent.client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * REST client for communicating with the Snapshot microservice.
 * Uses synchronous API calls for inter-service communication.
 */
@Slf4j
@Component
public class SnapshotServiceClient {
    
    private final WebClient webClient;
    private final ipl.pdms.outbox.runtime.RuntimeOutboxControl runtime;
    
    public SnapshotServiceClient(@Value("${services.snapshot.url:http://localhost:8082}") String snapshotServiceUrl,
                                 ipl.pdms.outbox.runtime.RuntimeOutboxControl runtime) {
        this.runtime = runtime;
        this.webClient = WebClient.builder()
                .baseUrl(snapshotServiceUrl)
                .build();
    }
    
    /**
     * Creates snapshot for a person asynchronously.
     * This is an async operation - doesn't block the calling thread.
     */
    public void createSnapshotAsync(UUID personId) {
        try {
            String authToken = getAuthToken();
            var request = webClient.post()
                    .uri("/api/snapshot/person/{personId}", personId);
            
            if (authToken != null) {
                request = request.header("Authorization", "Bearer " + authToken);
            }
            
            request.retrieve()
                    .bodyToMono(Void.class)
                    .doOnSubscribe(subscription -> runtime.retainBusinessWork())
                    .doFinally(signal -> runtime.exitBusiness())
                    .subscribe(
                            result -> log.debug("Successfully created snapshot for person {}", personId),
                            error -> log.error("Failed to create snapshot for person {}: {}", personId, error.getMessage())
                    );
        } catch (Exception e) {
            log.error("Failed to create snapshot for person {}: {}", personId, e.getMessage(), e);
        }
    }
    
    private String getAuthToken() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String bearerToken = request.getHeader("Authorization");
                if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                    return bearerToken.substring(7);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract auth token: {}", e.getMessage());
        }
        return null;
    }
}

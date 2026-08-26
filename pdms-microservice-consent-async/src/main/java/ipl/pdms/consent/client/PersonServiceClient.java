package ipl.pdms.consent.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

/**
 * REST client for communicating with the Person microservice.
 */
@Component
public class PersonServiceClient {
    
    private final WebClient webClient;
    
    public PersonServiceClient(@Value("${services.person.url:http://localhost:8081}") String personServiceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(personServiceUrl)
                .build();
    }
    
    /**
     * Creates a person in the person service.
     * Returns the created person data including personId.
     */
    public Map<String, Object> createPerson(Object personRequest, String authToken) {
        return webClient.post()
                .uri("/api/person")
                .header("Authorization", "Bearer " + authToken)
                .bodyValue(personRequest)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }
    
    /**
     * Gets person data by personId.
     */
    public Map<String, Object> getPerson(UUID personId, String authToken) {
        return webClient.get()
                .uri("/api/person/{personId}", personId)
                .header("Authorization", "Bearer " + authToken)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
    }
}

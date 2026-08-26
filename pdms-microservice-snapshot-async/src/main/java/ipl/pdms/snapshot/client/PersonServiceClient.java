package ipl.pdms.snapshot.client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

/**
 * REST client for communicating with the Person microservice.
 */
@Slf4j
@Component
public class PersonServiceClient {
    
    private final WebClient webClient;
    
    public PersonServiceClient(@Value("${services.person.url:http://localhost:8081}") String personServiceUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(personServiceUrl)
                .build();
    }
    
    public Map<String, Object> getPersonData(UUID personId) {
        return get("/api/person/{personId}", personId.toString());
    }
    
    private Map<String, Object> get(String uri, String param) {
        String authToken = getAuthToken();
        var request = webClient.get().uri(uri, param);
        if (authToken != null) {
            request = request.header("Authorization", "Bearer " + authToken);
        }
        return request.retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
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

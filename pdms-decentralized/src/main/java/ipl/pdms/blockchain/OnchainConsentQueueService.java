package ipl.pdms.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import ipl.pdms.domain.Consent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "pdms.blockchain.enabled", havingValue = "true")
public class OnchainConsentQueueService {

    private final OnchainConsentOutboxRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void enqueue(String anchorType, UUID personId, Consent consent) {
        try {
            String json = objectMapper.writeValueAsString(consent);
            repository.insert(UUID.randomUUID(), consent.id(), personId, anchorType, json);
            log.debug("Queued onchain anchor {} for consent {}", anchorType, consent.id());
        } catch (Exception e) {
            log.error("Failed to queue onchain consent outbox for {}", consent.id(), e);
            throw new IllegalStateException("Failed to queue onchain consent event", e);
        }
    }
}

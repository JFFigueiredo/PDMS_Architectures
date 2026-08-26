package ipl.pdms.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ipl.pdms.domain.Consent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentLedgerAnchorService {

    private final ConsentLedgerAnchorRepository anchorRepository;
    private final ConsentPayloadHasher consentPayloadHasher;

    private final ObjectMapper canonicalObjectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Transactional
    public void anchor(String anchorType, UUID personId, Consent consent) {
        String payloadHash = consentPayloadHasher.payloadHash(anchorType, personId, consent);
        String previousHash = anchorRepository.findLatestChainHash(consent.id());
        String chainHash = consentPayloadHasher.sha256Hex((previousHash == null ? "GENESIS" : previousHash) + ":" + payloadHash);

        anchorRepository.saveAnchor(
                UUID.randomUUID(),
                consent.id(),
                personId,
                anchorType,
                payloadHash,
                previousHash,
                chainHash,
                Instant.now()
        );
        log.debug("Anchored consent {} event {} with chain hash {}", consent.id(), anchorType, chainHash);
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void anchorFromOutboxPayload(Map<String, Object> payload) {
        String anchorType = String.valueOf(payload.get("anchorType"));
        UUID personId = UUID.fromString(String.valueOf(payload.get("personId")));
        Map<String, Object> consentMap = (Map<String, Object>) payload.get("consent");
        Consent consent = canonicalObjectMapper.convertValue(consentMap, Consent.class);
        anchor(anchorType, personId, consent);
    }

}

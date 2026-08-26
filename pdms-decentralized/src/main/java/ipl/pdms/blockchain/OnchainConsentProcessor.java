package ipl.pdms.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import ipl.pdms.domain.Consent;
import ipl.pdms.ledger.ConsentPayloadHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "pdms.blockchain.enabled", havingValue = "true")
public class OnchainConsentProcessor {

    private static final int MAX_RETRIES = 10;
    private static final int BATCH = 25;

    private final OnchainConsentOutboxRepository outboxRepository;
    private final BesuConsentAnchorClient besuClient;
    private final ConsentPayloadHasher consentPayloadHasher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${pdms.blockchain.scheduled-fixed-delay-ms:500}")
    public void processPending() {
        try {
            besuClient.ensureContractDeployed();
        } catch (Exception e) {
            log.warn("Besu not ready (deploy skipped this tick): {}", e.getMessage());
            return;
        }

        List<OnchainConsentOutboxRepository.PendingOnchainRow> pending = outboxRepository.findPending(BATCH);
        for (OnchainConsentOutboxRepository.PendingOnchainRow row : pending) {
            try {
                Consent consent = objectMapper.readValue(row.consentJson(), Consent.class);
                String payloadHash = consentPayloadHasher.payloadHash(row.anchorType(), row.personId(), consent);
                int action = mapAction(row.anchorType());
                TransactionReceipt receipt = besuClient.sendAnchor(row.consentId(), row.personId(), payloadHash, action);
                if (!receipt.isStatusOK()) {
                    throw new IllegalStateException("receipt status not OK: " + receipt.getStatus());
                }
                long block = receipt.getBlockNumber() != null ? receipt.getBlockNumber().longValue() : -1L;
                outboxRepository.markConfirmed(row.id(), receipt.getTransactionHash(), block);
                log.info("Onchain anchor confirmed consent {} tx {}", row.consentId(), receipt.getTransactionHash());
            } catch (Exception e) {
                handleFailure(row.id(), e);
            }
        }
    }

    private void handleFailure(java.util.UUID id, Exception e) {
        outboxRepository.bumpRetry(id, e.getMessage());
        int retries = outboxRepository.countRetry(id);
        if (retries >= MAX_RETRIES) {
            outboxRepository.markFailed(id, e.getMessage());
            log.error("Onchain outbox {} failed after {} retries", id, retries, e);
        } else {
            log.warn("Onchain outbox {} will retry ({}): {}", id, retries, e.getMessage());
        }
    }

    private static int mapAction(String anchorType) {
        return switch (anchorType) {
            case "CONSENT_CREATED" -> 0;
            case "CONSENT_UPDATED" -> 1;
            case "CONSENT_WITHDRAWN" -> 2;
            case "CONSENT_DELETED" -> 3;
            default -> 0;
        };
    }
}

package ipl.pdms.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import ipl.pdms.domain.Consent;
import ipl.pdms.ledger.ConsentPayloadHasher;
import ipl.pdms.outbox.runtime.RuntimeOutboxControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

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
    private final RuntimeOutboxControl runtimeControl;

    @Scheduled(fixedDelayString = "${pdms.blockchain.scheduled-fixed-delay-ms:500}")
    public void processPending() {
        // One row per transaction: policy changes may take effect between anchors.
        Set<UUID> attempted = new HashSet<>();
        for (int i = 0; i < BATCH; i++) {
            boolean[] found = {false};
            try {
                runtimeControl.withOnchainEvents(types -> found[0] = processAllowed(types, attempted));
            } catch (Exception e) {
                log.error("Error processing guarded on-chain outbox", e);
                return;
            }
            if (!found[0]) return;
        }
    }

    private boolean processAllowed(Set<String> allowedTypes, Set<UUID> attempted) {
        List<OnchainConsentOutboxRepository.PendingOnchainRow> pending =
                outboxRepository.findPending(1, allowedTypes, Set.copyOf(attempted));
        if (pending.isEmpty()) return false;
        try {
            besuClient.ensureContractDeployed();
        } catch (Exception e) {
            log.warn("Besu not ready (deploy skipped this tick): {}", e.getMessage());
            return false;
        }

        for (OnchainConsentOutboxRepository.PendingOnchainRow row : pending) {
            // Failed rows stay PENDING, but must wait for the next scheduled invocation.
            attempted.add(row.id());
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
        return true;
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

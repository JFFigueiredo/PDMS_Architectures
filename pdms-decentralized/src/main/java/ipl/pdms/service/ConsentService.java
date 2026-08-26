package ipl.pdms.service;

import ipl.pdms.audit.AuditService;
import ipl.pdms.audit.AuditEventType;
import ipl.pdms.db.SimpleConsentMapper;
import ipl.pdms.domain.Consent;
import ipl.pdms.outbox.OutboxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ipl.pdms.blockchain.OnchainConsentQueueService;
import ipl.pdms.metrics.GdprMetrics;
import org.springframework.beans.factory.ObjectProvider;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {
    
    private final SimpleConsentMapper consentMapper;
    private final AuditService auditService;
    private final ConsentChangeEventService changeEventService;
    private final OutboxService outboxService;
    private final GdprMetrics gdprMetrics;
    private final ObjectProvider<OnchainConsentQueueService> onchainConsentQueueService;

    @Transactional
    public Consent createConsent(UUID personId, Consent consent) {
        consentMapper.save(consent);
        gdprMetrics.consentGranted(consent.consentType());
        log.info("Created consent {} for person {}", consent.id(), personId);
        
        HttpServletRequest request = getRequest();
        auditService.auditEvent(AuditEventType.CONSENT_CREATED, "CONSENT", personId, request, Map.of());
        enqueueLedgerAnchor("CONSENT_CREATED", personId, consent);
        maybeEnqueueOnchain("CONSENT_CREATED", personId, consent);
        
        try {
            changeEventService.publishConsentCreatedEvent(personId, consent);
        } catch (Exception e) {
            log.warn("Failed to publish consent created change event, but consent was created successfully: {}", e.getMessage());
        }
        
        return consent;
    }
    
    public List<Consent> getConsentsByPersonId(UUID personId) {
        return consentMapper.findByPersonId(personId);
    }
    
    public Consent getConsentById(UUID consentId) {
        return consentMapper.findById(consentId);
    }
    
    @Transactional
    public Consent updateConsent(UUID personId, UUID consentId, Consent consent) {
        Consent oldConsent = consentMapper.findById(consentId);
        
        consentMapper.update(consent);
        log.info("Updated consent {} for person {}", consentId, personId);
        
        HttpServletRequest request = getRequest();
        auditService.auditEvent(AuditEventType.CONSENT_UPDATED, "CONSENT", personId, request, Map.of());
        enqueueLedgerAnchor("CONSENT_UPDATED", personId, consent);
        maybeEnqueueOnchain("CONSENT_UPDATED", personId, consent);
        
        try {
            if (oldConsent != null) {
                changeEventService.publishConsentUpdatedEvent(personId, oldConsent, consent);
            } else {
                changeEventService.publishConsentCreatedEvent(personId, consent);
            }
        } catch (Exception e) {
            log.warn("Failed to publish consent updated change event, but consent was updated successfully: {}", e.getMessage());
        }
        
        return consent;
    }
    
    @Transactional
    public void deleteConsent(UUID personId, UUID consentId) {
        Consent existing = consentMapper.findById(consentId);
        consentMapper.delete(consentId);
        gdprMetrics.consentDeleted();
        log.info("Deleted consent {} for person {}", consentId, personId);
        
        HttpServletRequest request = getRequest();
        auditService.auditEvent(AuditEventType.CONSENT_DELETED, "CONSENT", personId, request, Map.of());
        Consent deletedAnchor = existing != null
                ? Consent.builder()
                .id(existing.id())
                .personId(existing.personId())
                .consentType(existing.consentType())
                .granted(existing.granted())
                .grantedAt(existing.grantedAt())
                .expiresAt(existing.expiresAt())
                .consentMethod(existing.consentMethod())
                .consentVersion(existing.consentVersion())
                .isActive(false)
                .withdrawalReason("CONSENT_DELETED")
                .withdrawnAt(Instant.now())
                .createdAt(existing.createdAt())
                .updatedAt(Instant.now())
                .version(existing.version() != null ? existing.version() + 1 : 1)
                .build()
                : Consent.builder()
                .id(consentId)
                .personId(personId)
                .consentType("DELETED")
                .granted(false)
                .consentMethod("SYSTEM")
                .consentVersion("N/A")
                .isActive(false)
                .withdrawalReason("CONSENT_DELETED")
                .withdrawnAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(1)
                .build();
        enqueueLedgerAnchor("CONSENT_DELETED", personId, deletedAnchor);
        maybeEnqueueOnchain("CONSENT_DELETED", personId, deletedAnchor);
        
        try {
            changeEventService.publishConsentDeletedEvent(personId, consentId);
        } catch (Exception e) {
            log.warn("Failed to publish consent deleted change event, but consent was deleted successfully: {}", e.getMessage());
        }
    }
    
    @Transactional
    public Consent withdrawConsent(UUID personId, UUID consentId, String withdrawalReason) {
        Consent existing = consentMapper.findById(consentId);
        if (existing == null) {
            throw new IllegalArgumentException("Consent not found: " + consentId);
        }
        
        Consent withdrawn = Consent.builder()
                .id(existing.id())
                .personId(existing.personId())
                .consentType(existing.consentType())
                .granted(false)
                .grantedAt(existing.grantedAt())
                .expiresAt(existing.expiresAt())
                .consentMethod(existing.consentMethod())
                .consentVersion(existing.consentVersion())
                .isActive(false)
                .withdrawalReason(withdrawalReason)
                .withdrawnAt(Instant.now())
                .createdAt(existing.createdAt())
                .updatedAt(Instant.now())
                .version(existing.version() != null ? existing.version() + 1 : 1)
                .build();
        
        consentMapper.update(withdrawn);
        gdprMetrics.consentRevoked();
        log.info("Withdrawn consent {} for person {}", consentId, personId);
        
        HttpServletRequest request = getRequest();
        auditService.auditEvent(AuditEventType.CONSENT_WITHDRAWN, "CONSENT", personId, request, 
                Map.of("consentId", consentId.toString(), "withdrawalReason", withdrawalReason != null ? withdrawalReason : ""));
        enqueueLedgerAnchor("CONSENT_WITHDRAWN", personId, withdrawn);
        maybeEnqueueOnchain("CONSENT_WITHDRAWN", personId, withdrawn);
        
        try {
            changeEventService.publishConsentUpdatedEvent(personId, existing, withdrawn);
        } catch (Exception e) {
            log.warn("Failed to publish consent withdrawal change event, but consent was withdrawn successfully: {}", e.getMessage());
        }
        
        return withdrawn;
    }
    
    private HttpServletRequest getRequest() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private void enqueueLedgerAnchor(String anchorType, UUID personId, Consent consent) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("anchorType", anchorType);
        payload.put("personId", personId.toString());
        payload.put("consent", consent);
        outboxService.saveEvent("CONSENT", consent.id().toString(), "CONSENT_LEDGER_ANCHOR", payload);
    }

    private void maybeEnqueueOnchain(String anchorType, UUID personId, Consent consent) {
        onchainConsentQueueService.ifAvailable(s -> s.enqueue(anchorType, personId, consent));
    }
}


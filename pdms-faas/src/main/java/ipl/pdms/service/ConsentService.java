package ipl.pdms.service;

import ipl.pdms.audit.AuditService;
import ipl.pdms.audit.AuditEventType;
import ipl.pdms.db.SimpleConsentMapper;
import ipl.pdms.domain.Consent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ipl.pdms.metrics.GdprMetrics;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {
    
    private final SimpleConsentMapper consentMapper;
    private final AuditService auditService;
    private final ConsentChangeEventService changeEventService;
    private final GdprMetrics gdprMetrics;
    
    @Transactional
    public Consent createConsent(UUID personId, Consent consent) {
        consentMapper.save(consent);
        gdprMetrics.consentGranted(consent.consentType());
        log.info("Created consent {} for person {}", consent.id(), personId);
        
        HttpServletRequest request = getRequest();
        auditService.auditEvent(AuditEventType.CONSENT_CREATED, "CONSENT", personId, request, Map.of());
        
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
        consentMapper.delete(consentId);
        gdprMetrics.consentDeleted();
        log.info("Deleted consent {} for person {}", consentId, personId);
        
        HttpServletRequest request = getRequest();
        auditService.auditEvent(AuditEventType.CONSENT_DELETED, "CONSENT", personId, request, Map.of());
        
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
}


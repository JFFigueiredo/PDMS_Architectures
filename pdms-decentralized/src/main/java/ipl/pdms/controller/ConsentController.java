package ipl.pdms.controller;

import ipl.pdms.controller.dto.CreateConsentRequestDto;
import ipl.pdms.domain.Consent;
import ipl.pdms.service.ConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/person/{personId}/consents")
@RequiredArgsConstructor
@Slf4j
public class ConsentController {
    
    private final ConsentService consentService;
    
    @PostMapping
    public ResponseEntity<Consent> createConsent(
            @PathVariable UUID personId,
            @Valid @RequestBody CreateConsentRequestDto request) {
        Consent consent = Consent.builder()
                .id(UUID.randomUUID())
                .personId(personId)
                .consentType(request.consentType())
                .granted(request.granted())
                .grantedAt(request.granted() ? Instant.now() : null)
                .expiresAt(request.expiresAt())
                .consentMethod(request.consentMethod())
                .consentVersion(request.consentVersion())
                .isActive(request.granted())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .version(1)
                .build();
        
        Consent created = consentService.createConsent(personId, consent);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
    
    @GetMapping
    public ResponseEntity<List<Consent>> listConsents(@PathVariable UUID personId) {
        List<Consent> consents = consentService.getConsentsByPersonId(personId);
        return ResponseEntity.ok(consents);
    }
    
    @GetMapping("/{consentId}")
    public ResponseEntity<Consent> getConsent(
            @PathVariable UUID personId,
            @PathVariable UUID consentId) {
        Consent consent = consentService.getConsentById(consentId);
        if (consent == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(consent);
    }
    
    @PutMapping("/{consentId}")
    public ResponseEntity<Consent> updateConsent(
            @PathVariable UUID personId,
            @PathVariable UUID consentId,
            @Valid @RequestBody CreateConsentRequestDto request) {
        Consent existing = consentService.getConsentById(consentId);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        
        Consent updated = Consent.builder()
                .id(consentId)
                .personId(personId)
                .consentType(request.consentType())
                .granted(request.granted())
                .grantedAt(request.granted() ? Instant.now() : existing.grantedAt())
                .expiresAt(request.expiresAt())
                .consentMethod(request.consentMethod())
                .consentVersion(request.consentVersion())
                .isActive(request.granted())
                .withdrawalReason(request.granted() ? null : request.withdrawalReason())
                .withdrawnAt(request.granted() ? null : Instant.now())
                .createdAt(existing.createdAt())
                .updatedAt(Instant.now())
                .version(existing.version() + 1)
                .build();
        
        Consent result = consentService.updateConsent(personId, consentId, updated);
        return ResponseEntity.ok(result);
    }
    
    @DeleteMapping("/{consentId}")
    public ResponseEntity<Map<String, String>> deleteConsent(
            @PathVariable UUID personId,
            @PathVariable UUID consentId) {
        Consent existing = consentService.getConsentById(consentId);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        
        consentService.deleteConsent(personId, consentId);
        return ResponseEntity.ok(Map.of("message", "Consent deleted", "consentId", consentId.toString()));
    }
    
    @PostMapping("/{consentId}/withdraw")
    public ResponseEntity<Consent> withdrawConsent(
            @PathVariable UUID personId,
            @PathVariable UUID consentId,
            @RequestBody(required = false) Map<String, String> request) {
        Consent existing = consentService.getConsentById(consentId);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        
        String withdrawalReason = request != null ? request.get("withdrawalReason") : null;
        Consent withdrawn = consentService.withdrawConsent(personId, consentId, withdrawalReason);
        return ResponseEntity.ok(withdrawn);
    }
}

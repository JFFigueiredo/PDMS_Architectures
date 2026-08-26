package ipl.pdms.shared.security;

import ipl.pdms.audit.domain.AuditEventType;
import ipl.pdms.audit.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for logging security events (failed logins, suspicious patterns).
 * Implements GDPR Article 32 - Security of processing.
 */
@Service
@RequiredArgsConstructor
public class SecurityEventService {

    private final AuditService auditService;

    public void logFailedLogin(String reason, String userId, HttpServletRequest request) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("reason", reason);
        eventData.put("userId", userId != null ? userId : "UNKNOWN");
        auditService.auditEvent(AuditEventType.SECURITY_LOGIN_FAILED, "SECURITY", null, request, eventData);
    }

    public void logSuspiciousAccess(String pattern, String userId, Map<String, Object> details, HttpServletRequest request) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("pattern", pattern);
        eventData.put("userId", userId != null ? userId : "UNKNOWN");
        if (details != null) eventData.putAll(details);
        auditService.auditEvent(AuditEventType.SECURITY_SUSPICIOUS_ACCESS, "SECURITY", null, request, eventData);
    }
}


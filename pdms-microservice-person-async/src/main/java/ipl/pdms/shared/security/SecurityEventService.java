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
        String[] requestInfo = extractRequestInfo(request, userId);
        auditService.auditEvent(AuditEventType.SECURITY_LOGIN_FAILED, "SECURITY", null, requestInfo, eventData);
    }

    public void logSuspiciousAccess(String pattern, String userId, Map<String, Object> details, HttpServletRequest request) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("pattern", pattern);
        eventData.put("userId", userId != null ? userId : "UNKNOWN");
        if (details != null) eventData.putAll(details);
        String[] requestInfo = extractRequestInfo(request, userId);
        auditService.auditEvent(AuditEventType.SECURITY_SUSPICIOUS_ACCESS, "SECURITY", null, requestInfo, eventData);
    }

    private String[] extractRequestInfo(HttpServletRequest request, String userId) {
        if (request == null) {
            return new String[]{null, null, null, userId};
        }
        String httpMethod = request.getMethod();
        String requestPath = request.getRequestURI();
        String ipAddress = extractIpAddress(request);
        return new String[]{httpMethod, requestPath, ipAddress, userId};
    }

    private String extractIpAddress(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isEmpty()) return xri;
        return request.getRemoteAddr();
    }
}


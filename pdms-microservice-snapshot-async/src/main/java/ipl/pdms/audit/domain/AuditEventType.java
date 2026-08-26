package ipl.pdms.audit.domain;

/**
 * Enumeration of all audit event types in the system.
 * Provides type safety and prevents typos in event type strings.
 */
public enum AuditEventType {
    PERSON_CREATED,
    PERSON_UPDATED,
    PERSON_DELETED,

    CONSENT_CREATED,
    CONSENT_UPDATED,
    CONSENT_DELETED,
    CONSENT_WITHDRAWN,

    SNAPSHOT_CREATED,

    SECURITY_LOGIN_FAILED,
    SECURITY_SUSPICIOUS_ACCESS;
    
    /**
     * Returns the event type as a string (same as enum name).
     */
    public String getValue() {
        return this.name();
    }
}



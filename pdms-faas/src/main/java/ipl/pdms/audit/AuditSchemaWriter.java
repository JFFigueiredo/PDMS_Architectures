package ipl.pdms.audit;

/**
 * Interface for schema-specific audit writers.
 * Each implementation saves audit events to its specific database schema format.
 */
public interface AuditSchemaWriter {
    /**
     * Saves an audit event to a specific database schema.
     * 
     * @param auditEvent the audit event to save
     */
    void save(AuditEvent auditEvent);
}





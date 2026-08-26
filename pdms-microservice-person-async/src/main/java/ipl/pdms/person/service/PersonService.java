package ipl.pdms.person.service;

import ipl.pdms.audit.domain.AuditEventType;
import ipl.pdms.audit.service.AuditService;
import ipl.pdms.person.controller.PersonDataConverter;
import ipl.pdms.person.controller.dto.CreatePersonRequestDto;
import ipl.pdms.person.repository.SchemaReader;
import ipl.pdms.person.repository.SchemaWriter;
import ipl.pdms.person.domain.PersonData;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Timer;
import ipl.pdms.person.metrics.GdprMetrics;

/**
 * Service for person operations using normalized database schema.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PersonService {
    private final PersonDataConverter converter;
    private final AuditService auditService;
    private final SchemaWriter writer;
    private final SchemaReader reader;
    private final PersonChangeEventService changeEventService;
    private final GdprMetrics gdprMetrics;

    /**
     * Saves a person to the normalized database schema.
     *
     * @param dto the DTO object from the controller
     * @return result map with person ID and status
     */
    @Transactional
    public UUID savePerson(CreatePersonRequestDto dto) {
        PersonData personData = converter.toPersonData(dto);
        writer.save(personData);

        try {
            auditService.auditEvent(AuditEventType.PERSON_CREATED, "PERSON", personData.id(), extractRequestInfoWithUserId(), Map.of());
        } catch (Exception auditEx) {
            log.warn("Failed to audit person creation, but person was created successfully: {}", auditEx.getMessage());
        }

        try {
            changeEventService.publishPersonCreatedEvent(personData);
        } catch (Exception e) {
            log.warn("Failed to publish person created change event, but person was created successfully: {}", e.getMessage());
        }

        return personData.id();
    }

    /**
     * Deletes a person.
     */
    @Transactional
    public Map<String, Object> deletePerson(UUID personId) {
        Timer.Sample sample = gdprMetrics.startDataDeletionTimer();
        try {
            writer.delete(personId);
            gdprMetrics.dataDeletionRequested();

            try {
                auditService.auditEvent(AuditEventType.PERSON_DELETED, "PERSON", personId, extractRequestInfoWithUserId(), Map.of());
            } catch (Exception e) {
                log.warn("Failed to audit: {}", e.getMessage());
            }

            try {
                changeEventService.publishPersonDeletedEvent(personId);
            } catch (Exception e) {
                log.warn("Failed to publish event: {}", e.getMessage());
            }

            return Map.of("personId", personId.toString());
        } finally {
            gdprMetrics.stopDataDeletionTimer(sample);
        }
    }

    /**
     * Updates a person.
     *
     * @param personId the UUID of the person to update
     * @param dto      the DTO object from the controller
     */
    @Transactional
    public Map<String, Object> updatePerson(UUID personId, CreatePersonRequestDto dto) {
        PersonData oldPersonData = reader.readPersonById(personId);
        if (oldPersonData == null) {
            throw new IllegalArgumentException("Person not found: " + personId);
        }

        PersonData dtoData = converter.toPersonData(dto, personId);
        PersonData newPersonData = PersonData.builder()
                .id(dtoData.id())
                .status(dtoData.status())
                .version(oldPersonData.version() != null ? oldPersonData.version() + 1 : 1)
                .createdAt(oldPersonData.createdAt())
                .updatedAt(Instant.now())
                .userAccounts(dtoData.userAccounts())
                .identities(dtoData.identities())
                .addresses(dtoData.addresses())
                .preferences(dtoData.preferences())
                .build();

        writer.saveOrUpdate(newPersonData);

        try {
            auditService.auditEvent(AuditEventType.PERSON_UPDATED, "PERSON", personId, extractRequestInfoWithUserId(), Map.of());
        } catch (Exception e) {
            log.warn("Failed to audit: {}", e.getMessage());
        }

        try {
            changeEventService.publishPersonUpdatedEvent(oldPersonData, newPersonData);
        } catch (Exception e) {
            log.warn("Failed to publish event: {}", e.getMessage());
        }

        return Map.of("personId", newPersonData.id().toString(), "status", newPersonData.status());
    }


    /**
     * Reads a person from the normalized database schema.
     *
     * @param personId the UUID of the person to read
     * @return the ipl.pdms.person.domain.PersonData domain object, or null if not found
     */
    public PersonData readPerson(UUID personId) {
        return reader.readPersonById(personId);
    }

    /**
     * Finds a person by email address.
     *
     * @param email the email address to search for
     * @return the ipl.pdms.person.domain.PersonData if found, or null if not found
     */
    public PersonData findPersonByEmail(String email) {
        return reader.findByEmail(email);
    }

    /**
     * Finds a person by phone number.
     *
     * @param phone the phone number to search for
     * @return the ipl.pdms.person.domain.PersonData if found, or null if not found
     */
    public PersonData findPersonByPhone(String phone) {
        return reader.findByPhone(phone);
    }

    private HttpServletRequest getRequest() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private String[] extractRequestInfo() {
        HttpServletRequest request = getRequest();
        if (request == null) return new String[]{null, null, null};
        String httpMethod = request.getMethod();
        String requestPath = request.getRequestURI();
        String ipAddress = extractIpAddress(request);
        return new String[]{httpMethod, requestPath, ipAddress};
    }

    private String[] extractRequestInfoWithUserId() {
        HttpServletRequest request = getRequest();
        if (request == null) return new String[]{null, null, null, null};
        String httpMethod = request.getMethod();
        String requestPath = request.getRequestURI();
        String ipAddress = extractIpAddress(request);
        String userId = extractUserId();
        return new String[]{httpMethod, requestPath, ipAddress, userId};
    }

    private String extractUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "SYSTEM";
    }

    private String extractIpAddress(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isEmpty()) return xri;
        return request.getRemoteAddr();
    }
}

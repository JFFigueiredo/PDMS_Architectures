package ipl.pdms.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * Writes person IDs (without consents) to CSV file on application startup for JMeter tests.
 * Also writes emails and phones to identities_person.csv for snapshot operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonIdForTesting implements CommandLineRunner {

    private static final String CSV_FILE = "person_id_for_consents.csv";
    private static final String IDENTITIES_CSV_FILE = "identities_person.csv";
    private final JdbcTemplate jdbcTemplate;

    private static final int MAX_PERSON_IDS = 10000;

    @Override
    public void run(String... args) {
        log.info("=== PersonIdForTesting: Starting UUID export ===");
        try {
            String filePath = findOrCreateFile(CSV_FILE);
            List<UUID> personIds = queryPersonIds();
            writeToFile(filePath, personIds);
            log.info("=== PersonIdForTesting: Exported {} UUIDs to {}", personIds.size(), filePath);
        } catch (Exception e) {
            log.error("=== PersonIdForTesting: Failed to export UUIDs ===");
            log.error("Error: {}", e.getMessage(), e);
        }

        log.info("=== PersonIdForTesting: Starting identities export ===");
        try {
            String identitiesFilePath = findOrCreateFile(IDENTITIES_CSV_FILE);
            List<IdentityRecord> identities = queryIdentities();
            writeIdentitiesToFile(identitiesFilePath, identities);
            log.info("=== PersonIdForTesting: Exported {} identities to {}", identities.size(), identitiesFilePath);
        } catch (Exception e) {
            log.error("=== PersonIdForTesting: Failed to export identities ===");
            log.error("Error: {}", e.getMessage(), e);
        }
    }

    private List<UUID> queryPersonIdsWithoutConsents() {
        String sql = "SELECT p.id FROM person p LEFT JOIN consent c ON p.id = c.person_id WHERE c.id IS NULL ORDER BY p.created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("id"), MAX_PERSON_IDS);
    }

    private List<UUID> queryPersonIds() {
        String sql = "SELECT id FROM person ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("id"), MAX_PERSON_IDS);
    }

    private String findOrCreateFile(String fileName) {
        // Write to working directory (where JMeter expects it)
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        
        // Prefer jmeter/scripts subdirectory if it exists (for Docker volume mount)
        Path jmeterDir = workingDir.resolve("jmeter");
        Path scriptsDir = jmeterDir.resolve("scripts");
        if (java.nio.file.Files.exists(scriptsDir) || scriptsDir.toFile().mkdirs()) {
            Path jmeterPath = scriptsDir.resolve(fileName);
            log.info("Using jmeter/scripts directory: {}", jmeterPath.toAbsolutePath());
            return jmeterPath.toAbsolutePath().toString();
        }
        
        // Fallback to working directory
        Path filePath = workingDir.resolve(fileName);
        log.info("Using working directory: {}", filePath.toAbsolutePath());
        return filePath.toAbsolutePath().toString();
    }

    private void writeToFile(String filePath, List<UUID> personIds) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            if (personIds.isEmpty()) {
                pw.println("00000000-0000-0000-0000-000000000000");
                log.warn("No persons without consents found, writing placeholder UUID");
            } else {
                personIds.forEach(pw::println);
            }
        }
    }

    private List<IdentityRecord> queryIdentities() {
        // First get person IDs that have contact identities, ordered by created_at
        // Then join to get one email and one phone per person
        String sql = """
            SELECT 
                (SELECT ci.value FROM contact_identity ci 
                 WHERE ci.person_id = p.id AND ci.type = 'EMAIL' LIMIT 1) AS email,
                (SELECT ci.value FROM contact_identity ci 
                 WHERE ci.person_id = p.id AND ci.type = 'PHONE' LIMIT 1) AS phone
            FROM person p
            WHERE p.id IN (
                SELECT DISTINCT person_id 
                FROM contact_identity 
                WHERE type IN ('EMAIL', 'PHONE')
            )
            ORDER BY p.created_at DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String email = rs.getString("email");
            String phone = rs.getString("phone");
            return new IdentityRecord(
                email != null ? email : "",
                phone != null ? phone : ""
            );
        }, MAX_PERSON_IDS);
    }

    private void writeIdentitiesToFile(String filePath, List<IdentityRecord> identities) throws Exception {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            // Write header
            pw.println("EMAIL,PHONE");
            
            if (identities.isEmpty()) {
                pw.println("test@example.com,+1-555-0000");
                log.warn("No identities found, writing placeholder");
            } else {
                identities.forEach(identity -> {
                    pw.println(identity.email() + "," + identity.phone());
                });
            }
        }
    }

    private record IdentityRecord(String email, String phone) {}
}


package ipl.pdms.db;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Utility class for database operations.
 * Provides common helper methods used across schema writers.
 */
public final class DbUtils {

    private DbUtils() {
    }

    /**
     * Converts an Object to Timestamp.
     * Handles Instant, Timestamp, and null values.
     *
     * @param value the value to convert
     * @return Timestamp or null
     */
    public static Timestamp toTimestamp(Object value) {
        if (value == null) return null;
        if (value instanceof Instant) return Timestamp.from((Instant) value);
        if (value instanceof Timestamp) return (Timestamp) value;
        return null;
    }

    /**
     * Converts an Instant to Timestamp.
     *
     * @param instant the Instant to convert
     * @return Timestamp or null
     */
    public static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}


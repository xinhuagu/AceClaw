package dev.aceclaw.daemon.cron;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.util.BitSet;
import java.util.Objects;

/**
 * Minimal cron expression parser supporting standard 5-field format.
 *
 * <p>Format: {@code minute hour dayOfMonth month dayOfWeek}
 *
 * <p>Supported syntax per field:
 * <ul>
 *   <li>{@code *} — matches all values</li>
 *   <li>{@code N} — matches exact value (e.g. {@code 5})</li>
 *   <li>{@code N-M} — matches range inclusive (e.g. {@code 1-5})</li>
 *   <li>{@code * /N} or {@code M/N} — step values (e.g. {@code * /15}, {@code 0/10})</li>
 *   <li>{@code N,M,K} — comma-separated list (e.g. {@code 1,15,30})</li>
 * </ul>
 *
 * <p>Ranges:
 * <ul>
 *   <li>minute: 0-59</li>
 *   <li>hour: 0-23</li>
 *   <li>dayOfMonth: 1-31</li>
 *   <li>month: 1-12</li>
 *   <li>dayOfWeek: 0-7 (0 and 7 both mean Sunday, per POSIX cron convention)</li>
 * </ul>
 *
 * <p>This implementation does not support named months/days, L, W, # modifiers,
 * or 6/7-field Quartz-style expressions. It is intentionally minimal.
 */
public final class CronExpression {

    private final String expression;
    private final BitSet minutes;   // 0-59
    private final BitSet hours;     // 0-23
    private final BitSet daysOfMonth; // 1-31
    private final BitSet months;    // 1-12
    private final BitSet daysOfWeek;  // 0-6 (Sunday=0)

    private CronExpression(String expression, BitSet minutes, BitSet hours,
                           BitSet daysOfMonth, BitSet months, BitSet daysOfWeek) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
    }

    /**
     * Parses a 5-field cron expression.
     *
     * @param expr the cron expression string
     * @return the parsed expression
     * @throws IllegalArgumentException if the expression is invalid
     */
    public static CronExpression parse(String expr) {
        Objects.requireNonNull(expr, "Cron expression must not be null");
        String trimmed = expr.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "Cron expression must have exactly 5 fields (minute hour dayOfMonth month dayOfWeek), got "
                            + parts.length + ": " + expr);
        }

        BitSet minutes = parseField(parts[0], 0, 59, "minute");
        BitSet hours = parseField(parts[1], 0, 23, "hour");
        BitSet daysOfMonth = parseField(parts[2], 1, 31, "dayOfMonth");
        BitSet months = parseField(parts[3], 1, 12, "month");
        BitSet daysOfWeek = parseDayOfWeek(parts[4]);

        return new CronExpression(trimmed, minutes, hours, daysOfMonth, months, daysOfWeek);
    }

    /**
     * Returns the next fire time strictly after the given instant.
     *
     * @param after the instant to search from (exclusive)
     * @param zone  the timezone for cron evaluation
     * @return the next fire time, or null if no match within ~4 years
     */
    public Instant nextFireTime(Instant after, ZoneId zone) {
        LocalDateTime dt = LocalDateTime.ofInstant(after, zone)
                .withSecond(0).withNano(0)
                .plusMinutes(1);

        // Search forward up to 4 years (to handle leap years and complex expressions)
        LocalDateTime limit = dt.plusYears(4);

        while (dt.isBefore(limit)) {
            // Check month
            if (!months.get(dt.getMonthValue())) {
                dt = dt.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }

            // Check day of month
            if (!daysOfMonth.get(dt.getDayOfMonth())) {
                dt = dt.plusDays(1).withHour(0).withMinute(0);
                continue;
            }

            // Check day of week (Java DayOfWeek: MONDAY=1..SUNDAY=7, cron: Sunday=0)
            int cronDow = javaDowToCron(dt.getDayOfWeek());
            if (!daysOfWeek.get(cronDow)) {
                dt = dt.plusDays(1).withHour(0).withMinute(0);
                continue;
            }

            // Check hour
            if (!hours.get(dt.getHour())) {
                dt = dt.plusHours(1).withMinute(0);
                continue;
            }

            // Check minute
            if (!minutes.get(dt.getMinute())) {
                dt = dt.plusMinutes(1);
                continue;
            }

            return dt.atZone(zone).toInstant();
        }

        return null; // No match within search window
    }

    /**
     * Convenience overload using system default timezone.
     */
    public Instant nextFireTime(Instant after) {
        return nextFireTime(after, ZoneId.systemDefault());
    }

    /**
     * Returns the original expression string.
     */
    public String expression() {
        return expression;
    }

    /**
     * Returns true if the given instant matches this cron expression.
     */
    public boolean matches(Instant instant, ZoneId zone) {
        LocalDateTime dt = LocalDateTime.ofInstant(instant, zone);
        return minutes.get(dt.getMinute())
                && hours.get(dt.getHour())
                && daysOfMonth.get(dt.getDayOfMonth())
                && months.get(dt.getMonthValue())
                && daysOfWeek.get(javaDowToCron(dt.getDayOfWeek()));
    }

    @Override
    public String toString() {
        return "CronExpression[" + expression + "]";
    }

    // -- Parsing internals --

    static BitSet parseField(String field, int min, int max, String fieldName) {
        var bits = new BitSet(max + 1);
        for (String part : field.split(",")) {
            parseAtom(part.trim(), min, max, fieldName, bits);
        }
        if (bits.isEmpty()) {
            throw new IllegalArgumentException(
                    "No valid values in " + fieldName + " field: " + field);
        }
        return bits;
    }

    private static void parseAtom(String atom, int min, int max, String fieldName, BitSet bits) {
        // Handle step: */N or M/N or M-N/S
        if (atom.contains("/")) {
            String[] stepParts = atom.split("/", 2);
            int step = parseInt(stepParts[1], fieldName);
            if (step <= 0) {
                throw new IllegalArgumentException(
                        "Step value must be positive in " + fieldName + ": " + atom);
            }

            int start = min;
            int end = max;
            String base = stepParts[0];
            if (!"*".equals(base)) {
                if (base.contains("-")) {
                    String[] range = base.split("-", 2);
                    start = parseInt(range[0], fieldName);
                    end = parseInt(range[1], fieldName);
                } else {
                    start = parseInt(base, fieldName);
                }
            }

            validateRange(start, end, min, max, fieldName, atom);
            for (int i = start; i <= end; i += step) {
                bits.set(i);
            }
            return;
        }

        // Handle range: N-M
        if (atom.contains("-")) {
            String[] range = atom.split("-", 2);
            int start = parseInt(range[0], fieldName);
            int end = parseInt(range[1], fieldName);
            validateRange(start, end, min, max, fieldName, atom);
            for (int i = start; i <= end; i++) {
                bits.set(i);
            }
            return;
        }

        // Handle wildcard
        if ("*".equals(atom)) {
            for (int i = min; i <= max; i++) {
                bits.set(i);
            }
            return;
        }

        // Exact value
        int val = parseInt(atom, fieldName);
        if (val < min || val > max) {
            throw new IllegalArgumentException(
                    fieldName + " value " + val + " out of range [" + min + "-" + max + "]");
        }
        bits.set(val);
    }

    /**
     * Parses the dayOfWeek field with special handling: 0 and 7 both map to Sunday (0).
     */
    private static BitSet parseDayOfWeek(String field) {
        BitSet bits = parseField(field, 0, 7, "dayOfWeek");
        // Normalize: 7 (Sunday in some cron implementations) -> 0
        if (bits.get(7)) {
            bits.set(0);
            bits.clear(7);
        }
        return bits;
    }

    private static int parseInt(String s, String fieldName) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid number in " + fieldName + " field: '" + s + "'");
        }
    }

    private static void validateRange(int start, int end, int min, int max,
                                       String fieldName, String atom) {
        if (start < min || end > max || start > end) {
            throw new IllegalArgumentException(
                    "Invalid range in " + fieldName + " field: " + atom
                            + " (valid range: " + min + "-" + max + ")");
        }
    }

    /**
     * Converts Java's {@link DayOfWeek} (MONDAY=1..SUNDAY=7) to cron convention (Sunday=0..Saturday=6).
     */
    private static int javaDowToCron(DayOfWeek dow) {
        return dow == DayOfWeek.SUNDAY ? 0 : dow.getValue();
    }
}

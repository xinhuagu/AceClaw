package dev.aceclaw.daemon.cron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CronExpression} — parsing and next fire time calculation.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class CronExpressionTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    // -- Parsing tests --

    @Test
    void parseEveryMinute() {
        var expr = CronExpression.parse("* * * * *");
        assertThat(expr.expression()).isEqualTo("* * * * *");
    }

    @Test
    void parseSpecificValues() {
        var expr = CronExpression.parse("30 2 15 6 3");
        assertThat(expr.expression()).isEqualTo("30 2 15 6 3");
    }

    @Test
    void parseRange() {
        var expr = CronExpression.parse("0-30 9-17 * * 1-5");
        assertThat(expr.expression()).isEqualTo("0-30 9-17 * * 1-5");
    }

    @Test
    void parseStep() {
        var expr = CronExpression.parse("*/15 */2 * * *");
        assertThat(expr.expression()).isEqualTo("*/15 */2 * * *");
    }

    @Test
    void parseCommaList() {
        var expr = CronExpression.parse("0,15,30,45 * * * *");
        assertThat(expr.expression()).isEqualTo("0,15,30,45 * * * *");
    }

    @Test
    void parseRangeWithStep() {
        var expr = CronExpression.parse("0-30/10 * * * *");
        assertThat(expr.expression()).isEqualTo("0-30/10 * * * *");
    }

    @Test
    void parseTooFewFields_throws() {
        assertThatThrownBy(() -> CronExpression.parse("* * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 fields");
    }

    @Test
    void parseTooManyFields_throws() {
        assertThatThrownBy(() -> CronExpression.parse("* * * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 fields");
    }

    @Test
    void parseInvalidNumber_throws() {
        assertThatThrownBy(() -> CronExpression.parse("abc * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid number");
    }

    @Test
    void parseOutOfRange_throws() {
        assertThatThrownBy(() -> CronExpression.parse("60 * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void parseNegativeStep_throws() {
        assertThatThrownBy(() -> CronExpression.parse("*/-1 * * * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Step value must be positive");
    }

    // -- nextFireTime tests --

    @Test
    void everyMinute_nextIsOneMinuteLater() {
        var expr = CronExpression.parse("* * * * *");
        // 2024-01-15 10:30:00 UTC -> next should be 10:31
        Instant after = localToInstant(2024, 1, 15, 10, 30);
        Instant next = expr.nextFireTime(after, UTC);

        assertThat(next).isNotNull();
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 31, 0));
    }

    @Test
    void specificMinuteAndHour() {
        // "30 14 * * *" -> every day at 14:30
        var expr = CronExpression.parse("30 14 * * *");

        // Before the time today -> same day
        Instant after = localToInstant(2024, 1, 15, 10, 0);
        Instant next = expr.nextFireTime(after, UTC);
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 14, 30, 0));

        // After the time today -> next day
        after = localToInstant(2024, 1, 15, 15, 0);
        next = expr.nextFireTime(after, UTC);
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 16, 14, 30, 0));
    }

    @Test
    void every15Minutes() {
        // "*/15 * * * *" -> 0, 15, 30, 45 of every hour
        var expr = CronExpression.parse("*/15 * * * *");

        Instant after = localToInstant(2024, 1, 15, 10, 10);
        Instant next = expr.nextFireTime(after, UTC);
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 10, 15, 0));

        after = localToInstant(2024, 1, 15, 10, 45);
        next = expr.nextFireTime(after, UTC);
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 15, 11, 0, 0));
    }

    @Test
    void weekdaysOnly() {
        // "0 9 * * 1-5" -> 9:00 Monday through Friday
        var expr = CronExpression.parse("0 9 * * 1-5");

        // 2024-01-19 is a Friday
        Instant after = localToInstant(2024, 1, 19, 10, 0);
        Instant next = expr.nextFireTime(after, UTC);
        // Should skip Saturday (20) and Sunday (21), go to Monday (22)
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 22, 9, 0, 0));
    }

    @Test
    void sundayAs7() {
        // "0 8 * * 7" -> Sunday at 8:00 (7 = Sunday in some cron implementations)
        var expr = CronExpression.parse("0 8 * * 7");

        // 2024-01-15 is a Monday
        Instant after = localToInstant(2024, 1, 15, 0, 0);
        Instant next = expr.nextFireTime(after, UTC);
        // Next Sunday is Jan 21
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 21, 8, 0, 0));
    }

    @Test
    void sundayAs0() {
        // "0 8 * * 0" -> Sunday at 8:00 (0 = Sunday per POSIX)
        var expr = CronExpression.parse("0 8 * * 0");

        Instant after = localToInstant(2024, 1, 15, 0, 0);
        Instant next = expr.nextFireTime(after, UTC);
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 1, 21, 8, 0, 0));
    }

    @Test
    void specificDayOfMonth() {
        // "0 0 1 * *" -> 1st of every month at midnight
        var expr = CronExpression.parse("0 0 1 * *");

        Instant after = localToInstant(2024, 1, 15, 0, 0);
        Instant next = expr.nextFireTime(after, UTC);
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2024, 2, 1, 0, 0, 0));
    }

    @Test
    void specificMonth() {
        // "0 0 1 6 *" -> June 1st at midnight
        var expr = CronExpression.parse("0 0 1 6 *");

        Instant after = localToInstant(2024, 7, 1, 0, 0);
        Instant next = expr.nextFireTime(after, UTC);
        // Should wrap to next year
        assertThat(LocalDateTime.ofInstant(next, UTC))
                .isEqualTo(LocalDateTime.of(2025, 6, 1, 0, 0, 0));
    }

    @Test
    void matches_trueForMatchingInstant() {
        var expr = CronExpression.parse("30 14 * * *");
        Instant at = localToInstant(2024, 1, 15, 14, 30);
        assertThat(expr.matches(at, UTC)).isTrue();
    }

    @Test
    void matches_falseForNonMatchingInstant() {
        var expr = CronExpression.parse("30 14 * * *");
        Instant at = localToInstant(2024, 1, 15, 14, 31);
        assertThat(expr.matches(at, UTC)).isFalse();
    }

    @Test
    void toStringShowsExpression() {
        var expr = CronExpression.parse("*/5 * * * *");
        assertThat(expr.toString()).isEqualTo("CronExpression[*/5 * * * *]");
    }

    // -- Helpers --

    private static Instant localToInstant(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute, 0)
                .atZone(UTC).toInstant();
    }
}

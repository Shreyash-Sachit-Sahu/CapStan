package dev.capstan.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the schedule expressions in the policy table to an instant.
 *
 * <p>Supported: {@code +6h}, {@code next_payday_window},
 * {@code exp_backoff(4h, 2.0, jitter=0.2)}.
 *
 * <p><b>The jitter is deterministic.</b> The brief specifies random jitter, but
 * §1 also requires that the same case yields the same decision on every run, or
 * the backtest measures noise. Both hold if the jitter is derived from the case
 * id and attempt number rather than from a random source: spread across cases,
 * reproducible per case.
 */
public final class ScheduleExpression {

    private static final Pattern RELATIVE = Pattern.compile("^\\+(\\d+)([hmd])$");
    private static final Pattern BACKOFF = Pattern.compile(
            "^exp_backoff\\(\\s*(\\d+)([hmd])\\s*,\\s*([0-9.]+)\\s*(?:,\\s*jitter\\s*=\\s*([0-9.]+)\\s*)?\\)$");

    public static final String NEXT_PAYDAY_WINDOW = "next_payday_window";

    /** Salary credits land through the morning; retrying at midnight is pointless. */
    private static final int PAYDAY_BUFFER_HOURS = 6;

    public record Resolved(Instant at, String rationale) {
    }

    private ScheduleExpression() {
    }

    /**
     * @param expression  policy expression, or null meaning "now"
     * @param now         current instant from the injected clock
     * @param caseId      seeds deterministic jitter
     * @param attemptNo   ladder position, seeds backoff growth and jitter
     * @param salaryDay   inferred day-of-month, or null to default to the 1st
     */
    public static Resolved resolve(String expression, Instant now, UUID caseId,
                                   int attemptNo, Integer salaryDay) {
        if (expression == null || expression.isBlank()) {
            return new Resolved(now, "immediate");
        }

        Matcher relative = RELATIVE.matcher(expression.strip());
        if (relative.matches()) {
            Duration delay = duration(Long.parseLong(relative.group(1)), relative.group(2));
            return new Resolved(now.plus(delay), "fixed delay " + expression.strip());
        }

        if (NEXT_PAYDAY_WINDOW.equals(expression.strip())) {
            return nextPayday(now, salaryDay);
        }

        Matcher backoff = BACKOFF.matcher(expression.strip());
        if (backoff.matches()) {
            return expBackoff(backoff, now, caseId, attemptNo);
        }

        throw new IllegalStateException("unrecognised schedule expression: '" + expression + "'");
    }

    private static Resolved nextPayday(Instant now, Integer salaryDay) {
        int day = salaryDay == null ? 1 : salaryDay;
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate candidate = atDayOfMonth(today, day);
        Instant window = candidate.atStartOfDay(ZoneOffset.UTC)
                .plusHours(PAYDAY_BUFFER_HOURS).toInstant();
        if (!window.isAfter(now)) {
            candidate = atDayOfMonth(today.plusMonths(1), day);
            window = candidate.atStartOfDay(ZoneOffset.UTC)
                    .plusHours(PAYDAY_BUFFER_HOURS).toInstant();
        }
        String source = salaryDay == null
                ? "inferred salary date defaulted to the 1st (no prior successful debit observed)"
                : "inferred salary date " + day + " from prior successful debits";
        return new Resolved(window, source + "; +" + PAYDAY_BUFFER_HOURS + "h buffer");
    }

    private static LocalDate atDayOfMonth(LocalDate reference, int day) {
        return reference.withDayOfMonth(Math.min(day, reference.lengthOfMonth()));
    }

    private static Resolved expBackoff(Matcher m, Instant now, UUID caseId, int attemptNo) {
        Duration base = duration(Long.parseLong(m.group(1)), m.group(2));
        double factor = Double.parseDouble(m.group(3));
        double jitterFraction = m.group(4) == null ? 0.0 : Double.parseDouble(m.group(4));

        double seconds = base.getSeconds() * Math.pow(factor, Math.max(0, attemptNo - 1));
        double jitter = jitterFraction == 0.0 ? 0.0 : seconds * jitterFraction * signedUnit(caseId, attemptNo);
        long total = Math.max(1, Math.round(seconds + jitter));

        return new Resolved(now.plusSeconds(total),
                "exponential backoff from " + m.group(1) + m.group(2) + " at attempt " + attemptNo
                        + (jitterFraction == 0.0 ? "" : "; deterministic jitter seeded from case id"));
    }

    /** Stable value in [-1, 1) derived from the case and attempt. No RNG involved. */
    private static double signedUnit(UUID caseId, int attemptNo) {
        long mixed = caseId.getMostSignificantBits() * 31 + caseId.getLeastSignificantBits() + attemptNo;
        // Take the low 20 bits so the value is stable across JVMs and platforms.
        double unit = (mixed & 0xFFFFF) / (double) 0x100000;
        return unit * 2.0 - 1.0;
    }

    private static Duration duration(long amount, String unit) {
        return switch (unit) {
            case "h" -> Duration.ofHours(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalStateException("unknown time unit '" + unit + "'");
        };
    }
}

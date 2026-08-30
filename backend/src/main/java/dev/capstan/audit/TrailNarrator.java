package dev.capstan.audit;

import com.fasterxml.jackson.databind.JsonNode;
import dev.capstan.audit.AuditLedger.Event;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Renders a chain into plain English.
 *
 * <p>Templates, never a model. The ledger is the one place a paraphrase must not
 * drift: a narrative generated per-request could describe the same chain two
 * different ways, and the whole point of the trail is that it says exactly one
 * thing. This is also why it is safe to regenerate — the text is a function of
 * the events, so it can be recomputed and compared.
 */
@Service
public class TrailNarrator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.UK);

    public record Line(String at, String text) {
    }

    public List<Line> narrate(List<Event> events) {
        List<Line> lines = new ArrayList<>(events.size());
        LocalDate previousDate = null;

        for (Event event : events) {
            ZonedDateTime ist = event.occurredAt().atZone(IST);
            // The date only appears when it changes, so a same-day trail reads as
            // a sequence of times rather than a wall of repeated dates.
            String stamp = (previousDate == null || !previousDate.equals(ist.toLocalDate()))
                    ? DATE_TIME.format(ist) + " IST"
                    : TIME.format(ist) + " IST";
            previousDate = ist.toLocalDate();

            lines.add(new Line(stamp, describe(event)));
        }
        return lines;
    }

    private static String describe(Event event) {
        JsonNode p = CanonicalJson.read(event.payload());
        return switch (event.eventType()) {
            case AuditLedger.CASE_OPENED -> "Case opened for %s. Payment of %s failed."
                    .formatted(text(p, "customerRef", "the customer"), rupees(p, "amountPaise"));

            case AuditLedger.DIAGNOSIS_ATTEMPTED -> "Diagnosis attempted via %s%s."
                    .formatted(text(p, "method", "unknown tier"),
                            p.hasNonNull("cacheHit") && p.get("cacheHit").asBoolean()
                                    ? " (cached)" : "");

            case AuditLedger.DIAGNOSIS_RESOLVED -> "Diagnosed as %s (confidence %s)%s."
                    .formatted(human(text(p, "cause", "undiagnosed")),
                            text(p, "confidence", "0"),
                            p.hasNonNull("evidence") ? " — " + p.get("evidence").asText() : "");

            case AuditLedger.DECISION_MADE -> text(p, "humanReadable", "Decision recorded.");

            case AuditLedger.GUARDRAIL_BLOCKED -> "Held %s. %s %s: %s"
                    .formatted(human(text(p, "proposedAction", "the next action")),
                            text(p, "guardrailId", ""), text(p, "guardrailName", ""),
                            text(p, "reason", ""));

            case AuditLedger.COMMS_SUPPRESSED -> "Did not message the customer. %s %s: %s"
                    .formatted(text(p, "guardrailId", ""), text(p, "guardrailName", ""),
                            text(p, "reason", ""));

            case AuditLedger.INTERVENTION_SCHEDULED -> "Scheduled %s for %s (attempt %s)."
                    .formatted(human(text(p, "kind", "an action")),
                            when(p, "scheduledFor"), text(p, "attemptNo", "?"));

            case AuditLedger.DEBIT_INITIATED -> "Attempted a debit of %s on %s."
                    .formatted(rupees(p, "amountPaise"), text(p, "rail", "the mandate rail"));

            case AuditLedger.DEBIT_BLOCKED ->
                    "Did not retry — the previous attempt's outcome was not yet known.";

            case AuditLedger.GATEWAY_RESPONSE -> "SUCCEEDED".equals(text(p, "state", ""))
                    ? "Succeeded. %s recovered.".formatted(rupees(p, "amountPaise"))
                    : "Declined — %s.".formatted(text(p, "reason", "no reason given"));

            case AuditLedger.ATTEMPT_UNKNOWN ->
                    "Gateway did not confirm the outcome. Recorded as unknown; "
                            + "no further debit until it is reconciled.";

            case AuditLedger.RECONCILE_ATTEMPTED -> "Asked the gateway what happened: %s."
                    .formatted(text(p, "resolvedState", "still unknown"));

            case AuditLedger.COMMS_SENT -> "Sent a %s message (%s, %s copy)."
                    .formatted(human(text(p, "kind", "customer")), text(p, "locale", "en-IN"),
                            p.hasNonNull("fromTemplate") && p.get("fromTemplate").asBoolean()
                                    ? "template" : "generated");

            case AuditLedger.INTERVENTION_CANCELLED -> "Cancelled the queued %s — %s."
                    .formatted(human(text(p, "kind", "action")), text(p, "reason", "superseded"));

            case AuditLedger.CASE_TERMINATED -> "Case closed: %s. %s"
                    .formatted(human(text(p, "status", "")), text(p, "terminalReason", ""));

            default -> event.eventType() + " " + event.payload();
        };
    }

    /** An ISO instant in the payload, rendered in IST like every other time here. */
    private static String when(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return "later";
        }
        String raw = node.get(field).asText();
        try {
            return DATE_TIME.format(java.time.Instant.parse(raw).atZone(IST)) + " IST";
        } catch (java.time.format.DateTimeParseException notAnInstant) {
            return raw;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }

    /** SCHEDULED_RETRY -> "scheduled retry". */
    private static String human(String constant) {
        return constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String rupees(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return "an unrecorded amount";
        }
        long paise = node.get(field).asLong();
        long whole = paise / 100;
        long fraction = Math.abs(paise % 100);
        String grouped = groupIndian(whole);
        return fraction == 0 ? "Rs " + grouped : "Rs %s.%02d".formatted(grouped, fraction);
    }

    /** 1234567 -> 12,34,567. Lakhs and crores, not thousands. */
    private static String groupIndian(long value) {
        String digits = Long.toString(Math.abs(value));
        if (digits.length() <= 3) {
            return (value < 0 ? "-" : "") + digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);

        StringBuilder grouped = new StringBuilder();
        while (rest.length() > 2) {
            grouped.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        if (!rest.isEmpty()) {
            grouped.insert(0, rest);
        }
        return (value < 0 ? "-" : "") + grouped + "," + last3;
    }
}

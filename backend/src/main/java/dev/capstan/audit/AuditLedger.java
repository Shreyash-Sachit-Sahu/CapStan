package dev.capstan.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The append-only, tamper-evident record of what Capstan did — and, just as
 * deliberately, of what it declined to do.
 *
 * <p>A trail showing only completed actions cannot demonstrate that a system is
 * bounded; it looks identical to a system with no limits that happened not to
 * hit any. {@link #GUARDRAIL_BLOCKED}, {@link #COMMS_SUPPRESSED} and
 * {@link #DEBIT_BLOCKED} are the entries that make boundedness checkable.
 */
@Service
@RequiredArgsConstructor
public class AuditLedger {

    private static final Logger log = LoggerFactory.getLogger(AuditLedger.class);

    /** Bounded, so a genuinely broken chain cannot spin forever. */
    private static final int MAX_SEQ_ATTEMPTS = 5;

    public static final String ACTOR_DIAGNOSE = "SYSTEM:diagnose";
    public static final String ACTOR_POLICY = "SYSTEM:policy";
    public static final String ACTOR_EXECUTE = "SYSTEM:execute";
    public static final String ACTOR_GATEWAY = "GATEWAY";
    public static final String ACTOR_LOADER = "SYSTEM:loader";

    public static final String CASE_OPENED = "CASE_OPENED";
    public static final String DIAGNOSIS_ATTEMPTED = "DIAGNOSIS_ATTEMPTED";
    public static final String DIAGNOSIS_RESOLVED = "DIAGNOSIS_RESOLVED";
    public static final String DECISION_MADE = "DECISION_MADE";
    public static final String GUARDRAIL_BLOCKED = "GUARDRAIL_BLOCKED";
    public static final String INTERVENTION_SCHEDULED = "INTERVENTION_SCHEDULED";
    public static final String DEBIT_INITIATED = "DEBIT_INITIATED";
    /**
     * The Phase 05 invariant refusing to act: a debit whose predecessor's
     * outcome is not reconciled. Not in the brief's §3 list, and it is the most
     * demonstrative "action not taken" event the system produces.
     */
    public static final String DEBIT_BLOCKED = "DEBIT_BLOCKED";
    public static final String GATEWAY_RESPONSE = "GATEWAY_RESPONSE";
    public static final String ATTEMPT_UNKNOWN = "ATTEMPT_UNKNOWN";
    public static final String RECONCILE_ATTEMPTED = "RECONCILE_ATTEMPTED";
    public static final String COMMS_SENT = "COMMS_SENT";
    public static final String COMMS_SUPPRESSED = "COMMS_SUPPRESSED";
    public static final String INTERVENTION_CANCELLED = "INTERVENTION_CANCELLED";
    public static final String CASE_TERMINATED = "CASE_TERMINATED";

    private final AuditWriter writer;
    private final JdbcClient jdbc;
    private final Clock clock;

    public record Event(long id, UUID caseId, int seq, String eventType, String actor,
                        String payload, Instant occurredAt, String prevHash, String hash) {
    }

    public void record(UUID caseId, String actor, String eventType, Map<String, ?> payload) {
        record(caseId, actor, eventType, payload, Instant.now(clock));
    }

    /**
     * Appends an event, retrying if a concurrent writer takes the sequence
     * number first.
     *
     * <p>The loop is here rather than in {@link AuditWriter#append} because a
     * failed insert leaves its transaction rollback-only — see that class.
     */
    public void record(UUID caseId, String actor, String eventType,
                       Map<String, ?> payload, Instant at) {
        String canonical = CanonicalJson.canonicalise(payload);
        DataIntegrityViolationException lost = null;

        for (int attempt = 1; attempt <= MAX_SEQ_ATTEMPTS; attempt++) {
            try {
                writer.append(caseId, actor, eventType, canonical, at);
                return;
            } catch (DataIntegrityViolationException race) {
                // Another writer took this seq. A new transaction re-reads the
                // tail and tries the next one.
                lost = race;
                log.debug("Audit seq race on case {} (attempt {}/{})",
                        caseId, attempt, MAX_SEQ_ATTEMPTS);
            }
        }
        throw new IllegalStateException(
                "could not append " + eventType + " for case " + caseId
                        + " after " + MAX_SEQ_ATTEMPTS + " attempts", lost);
    }

    public List<Event> trail(UUID caseId) {
        return jdbc.sql("""
                select id, case_id, seq, event_type, actor, payload::text as payload,
                       occurred_at, prev_hash, hash
                  from audit_event
                 where case_id = :caseId
                 order by seq
                """)
                .param("caseId", caseId)
                .query(AuditLedger::map)
                .list();
    }

    /** Cases that have at least one event, so verification can report honestly. */
    public List<UUID> casesWithEvents() {
        return jdbc.sql("select distinct case_id from audit_event order by case_id")
                .query(UUID.class)
                .list();
    }

    public int caseCount() {
        return jdbc.sql("select count(*) from recovery_case").query(Integer.class).single();
    }

    private static Event map(ResultSet rs, int rowNum) throws SQLException {
        return new Event(
                rs.getLong("id"),
                rs.getObject("case_id", UUID.class),
                rs.getInt("seq"),
                rs.getString("event_type"),
                rs.getString("actor"),
                rs.getString("payload"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }
}

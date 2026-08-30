package dev.capstan.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.capstan.audit.AuditLedger;
import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles a {@link DecisionContext} from the database, runs the pure engine,
 * and persists the result as an intervention row.
 *
 * <p>All the I/O lives here so the engine stays deterministic and testable.
 */
@Service
@RequiredArgsConstructor
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules();

    /** G11's window: how far back "recent" reaches when measuring issuer health. */
    private static final Duration BREAKER_WINDOW = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final PolicyEngine engine;
    private final CopyWriter copyWriter;
    private final Clock clock;
    private final AuditLedger ledger;

    public DecisionRecord decide(UUID caseId) {
        return decide(caseId, Instant.now(clock));
    }

    /**
     * @param now supplied explicitly so the backtest can drive this from virtual
     *            time. A bean-swapped clock would let a path nobody threaded
     *            silently read wall time; a missing parameter does not compile.
     */
    public DecisionRecord decide(UUID caseId, Instant now) {
        return decide(loadContext(caseId, now).orElseThrow(() ->
                new IllegalArgumentException("no such case: " + caseId)));
    }

    /**
     * Decides from a context the caller already assembled. The backtest's
     * ablations use this: they reshape one field of the context and leave every
     * other code path identical, so a disabled mechanism is genuinely the only
     * difference between two runs.
     */
    @Transactional
    public DecisionRecord decide(DecisionContext ctx) {
        DecisionRecord record = engine.decide(ctx);
        record = attachCopy(record, ctx);
        persist(ctx, record);
        audit(ctx, record);
        return record;
    }

    /**
     * Writes the decision to the ledger, and — the part that matters — writes
     * each guardrail that stopped something as its own event.
     *
     * <p>The evaluations are already inside {@code decision_json}, but buried in a
     * blob they are not a trail: nothing can list them, count them, or render
     * them one at a time. A record of what Capstan chose <i>not</i> to do is the
     * only thing that distinguishes a bounded system from an unbounded one that
     * happened not to hit a limit, so those entries are first-class rows.
     */
    private void audit(DecisionContext ctx, DecisionRecord record) {
        Instant at = ctx.now();

        ledger.record(ctx.caseId(), AuditLedger.ACTOR_POLICY, AuditLedger.DECISION_MADE,
                Map.of("decisionId", record.decisionId().toString(),
                        "policyName", record.policyName(),
                        "policyVersion", record.policyVersion(),
                        "ladderPosition", record.ladderPosition(),
                        "proposedAction", record.proposedAction().name(),
                        "finalAction", record.finalAction().name(),
                        "humanReadable", String.valueOf(record.humanReadable())), at);

        for (DecisionRecord.GuardrailEvaluation evaluation : record.guardrails()) {
            if (!"BLOCK".equals(evaluation.result()) && !"DEFER".equals(evaluation.result())) {
                continue;
            }
            // Comms suppression reads differently from a blocked debit, and Phase
            // 08 renders them differently, so they are separate event types. The
            // split comes from the action that was blocked, not a hardcoded list
            // of which guardrails are about messaging.
            boolean comms = InterventionKind.valueOf(evaluation.appliedTo()).isComms;
            ledger.record(ctx.caseId(), AuditLedger.ACTOR_POLICY,
                    comms ? AuditLedger.COMMS_SUPPRESSED : AuditLedger.GUARDRAIL_BLOCKED,
                    Map.of("guardrailId", evaluation.id(),
                            "guardrailName", evaluation.name(),
                            "proposedAction", evaluation.appliedTo(),
                            "result", evaluation.result(),
                            "reason", String.valueOf(evaluation.detail())), at);
        }

        if (record.isTerminal()) {
            ledger.record(ctx.caseId(), AuditLedger.ACTOR_POLICY, AuditLedger.CASE_TERMINATED,
                    Map.of("status", String.valueOf(record.terminalStatus()),
                            "terminalReason", String.valueOf(record.terminalReason())), at);
        } else {
            ledger.record(ctx.caseId(), AuditLedger.ACTOR_POLICY,
                    AuditLedger.INTERVENTION_SCHEDULED,
                    Map.of("kind", record.finalAction().name(),
                            "scheduledFor", String.valueOf(record.scheduledFor()),
                            "attemptNo", record.ladderPosition() + 1,
                            "rationale", String.valueOf(record.scheduleRationale())), at);
        }
    }

    /**
     * Copy is generated only after the policy has authorised a comms action, and
     * only ever reaches the customer through the validator.
     */
    private DecisionRecord attachCopy(DecisionRecord record, DecisionContext ctx) {
        InterventionKind action = record.finalAction();
        if (!action.isComms) {
            return record;
        }
        String link = action == InterventionKind.REAUTH_LINK
                ? "https://pay.example.test/reauth/" + ctx.caseId() : null;

        String generated = copyWriter.draft(action, ctx.preferredLocale(), ctx.amountPaise(), link)
                .orElse(null);
        NudgeCopy.Copy copy = NudgeCopy.validated(generated, action, ctx.preferredLocale(),
                ctx.amountPaise(), link);

        if (copy.wasRejected()) {
            log.warn("Generated copy rejected for case {}: {}", ctx.caseId(), copy.rejectionReason());
        }
        return record.withCustomerMessage(new DecisionRecord.Message(
                copy.text(), copy.fromTemplate(), copy.rejectionReason()));
    }

    private void persist(DecisionContext ctx, DecisionRecord record) {
        String decisionJson;
        try {
            decisionJson = JSON.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise decision record", e);
        }

        // No expires_at. It is a claim lease owned by the worker that picks this
        // row up (Phase 05), not a scheduled window written at decision time.
        // Writing it here would mark every freshly decided intervention as
        // already in flight, and uq_case_in_flight would reject the case's next
        // decision.
        jdbc.sql("""
                insert into intervention (case_id, attempt_no, kind, scheduled_for, decision_json)
                values (:caseId, :attemptNo, :kind, :scheduledFor, cast(:decisionJson as jsonb))
                """)
                .param("caseId", ctx.caseId())
                .param("attemptNo", record.ladderPosition() + 1)
                .param("kind", record.finalAction().name())
                .param("scheduledFor", at(record.scheduledFor() == null ? ctx.now() : record.scheduledFor()))
                .param("decisionJson", decisionJson)
                .update();

        if (record.isTerminal()) {
            jdbc.sql("""
                    update recovery_case
                       set status = :status, terminal_reason = :reason, updated_at = :now
                     where id = :id
                    """)
                    .param("status", record.terminalStatus())
                    .param("reason", record.terminalReason())
                    .param("now", at(ctx.now()))
                    .param("id", ctx.caseId())
                    .update();
        } else {
            jdbc.sql("update recovery_case set status = 'SCHEDULED', updated_at = :now where id = :id")
                    .param("now", at(ctx.now()))
                    .param("id", ctx.caseId())
                    .update();
        }
    }

    public Optional<DecisionContext> loadContext(UUID caseId) {
        return loadContext(caseId, Instant.now(clock));
    }

    public Optional<DecisionContext> loadContext(UUID caseId, Instant now) {
        return jdbc.sql("""
                select c.id, c.amount_paise, c.first_failed_at, c.billing_cycle_end,
                       c.diagnosed_cause, c.diagnosis_confidence, c.diagnosis_method,
                       c.diagnosis_evidence, c.raw_error_reason,
                       m.status as mandate_status, m.valid_from, m.valid_until,
                       m.max_amount_paise, m.rail, m.alternate_rail,
                       cu.contact_opted_out, cu.risk_flagged, cu.preferred_locale,
                       c.reauth_requested_at, c.reauth_completed_at,
                       (select count(*) from intervention i where i.case_id = c.id) as ladder_position,
                       (select count(*) from payment_attempt p where p.case_id = c.id) as debit_attempts,
                       (select count(*) from intervention i where i.case_id = c.id
                          and i.kind in ('CUSTOMER_NUDGE','REAUTH_LINK')
                          and coalesce(i.outcome,'') <> 'CANCELLED') as comms_sent,
                       (select max(p.initiated_at) from payment_attempt p where p.case_id = c.id) as last_debit_at,
                       (select max(i.scheduled_for) from intervention i where i.case_id = c.id
                          and i.kind in ('CUSTOMER_NUDGE','REAUTH_LINK')) as last_comms_at,
                       (select count(*) from payment_attempt p
                          join recovery_case rc on rc.id = p.case_id
                         where rc.raw_error_reason = c.raw_error_reason
                           and p.initiated_at > :windowStart) as recent_attempts,
                       (select count(*) from payment_attempt p
                          join recovery_case rc on rc.id = p.case_id
                         where rc.raw_error_reason = c.raw_error_reason
                           and p.initiated_at > :windowStart
                           and p.state in ('FAILED','UNKNOWN')) as recent_failures,
                       (select extract(day from max(p.settled_at))::int from payment_attempt p
                          join recovery_case rc on rc.id = p.case_id
                         where rc.mandate_id = c.mandate_id and p.state = 'SUCCEEDED') as salary_day
                  from recovery_case c
                  join mandate m on m.id = c.mandate_id
                  join merchant_customer cu on cu.id = c.customer_id
                 where c.id = :id
                """)
                .param("id", caseId)
                .param("windowStart", at(now.minus(BREAKER_WINDOW)))
                .query((ResultSet rs, int rowNum) -> mapContext(rs, now))
                .optional();
    }

    private static DecisionContext mapContext(ResultSet rs, Instant now) throws SQLException {
        FailureCause cause = FailureCause.parse(rs.getString("diagnosed_cause"))
                .orElse(FailureCause.UNDIAGNOSED);
        String method = rs.getString("diagnosis_method");

        Long maxAmount = rs.getObject("max_amount_paise") == null
                ? null : rs.getLong("max_amount_paise");
        Boolean riskFlagged = rs.getObject("risk_flagged") == null
                ? null : rs.getBoolean("risk_flagged");
        Integer salaryDay = rs.getObject("salary_day") == null
                ? null : rs.getInt("salary_day");

        return new DecisionContext(
                rs.getObject("id", UUID.class),
                now,
                cause,
                rs.getObject("diagnosis_confidence") == null ? 0.0 : rs.getDouble("diagnosis_confidence"),
                parseMethod(method, rs.getObject("id", UUID.class)),
                rs.getString("diagnosis_evidence"),
                rs.getLong("amount_paise"),
                instant(rs, "first_failed_at"),
                instant(rs, "billing_cycle_end"),
                rs.getString("mandate_status"),
                instant(rs, "valid_from"),
                instant(rs, "valid_until"),
                maxAmount,
                rs.getString("rail"),
                rs.getString("alternate_rail"),
                rs.getBoolean("contact_opted_out"),
                riskFlagged,
                rs.getString("preferred_locale"),
                rs.getInt("ladder_position"),
                rs.getInt("debit_attempts"),
                rs.getInt("comms_sent"),
                instant(rs, "last_debit_at"),
                instant(rs, "last_comms_at"),
                salaryDay,
                rs.getInt("recent_attempts"),
                rs.getInt("recent_failures"),
                rs.getString("raw_error_reason"),
                instant(rs, "reauth_requested_at"),
                instant(rs, "reauth_completed_at"),
                java.util.Set.of());
    }

    /**
     * Fails loudly on a value the database accepted but the enum does not know.
     *
     * <p>This used to be a bare {@code valueOf}, whose {@code IllegalArgumentException}
     * was swallowed several frames up by a catch that logged and continued — so a
     * corrupt row read as "this case could not be replayed" rather than as
     * "something wrote a value nothing can interpret". V6 constrains the column;
     * this is what catches anything that got in before it, or around it.
     */
    private static Diagnosis.DiagnosisMethod parseMethod(String raw, UUID caseId) {
        if (raw == null) {
            return null;
        }
        try {
            return Diagnosis.DiagnosisMethod.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    "case " + caseId + " has diagnosis_method '" + raw + "', which is not a "
                            + "DiagnosisMethod (expected one of "
                            + java.util.Arrays.toString(Diagnosis.DiagnosisMethod.values()) + ")",
                    unknown);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

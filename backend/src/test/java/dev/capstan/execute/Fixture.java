package dev.capstan.execute;

import dev.capstan.policy.InterventionKind;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds throwaway cases for the execution tests.
 *
 * <p>Everything it creates carries {@code batch_label = 'test-exec'} so
 * {@link #clean} can remove it without touching {@code batch_v1}, which the
 * diagnosis and policy work depends on.
 */
public class Fixture {

    public static final String LABEL = "test-exec";

    private final JdbcClient jdbc;

    public Fixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A case the oracle says is recoverable right now, by any rail, with certainty. */
    public UUID recoverableCase(Instant firstFailedAt, long amountPaise) {
        return newCase(firstFailedAt, amountPaise, firstFailedAt.plusSeconds(30 * 86400),
                true, "ANY", 1.0);
    }

    /**
     * A case whose ladder debits BEFORE it asks for re-authorisation, and whose
     * re-auth never converts.
     *
     * <p>Both halves are required to expose state leaking between runs.
     * AUTHENTICATION_FAILED is [RAIL_SWITCH, REAUTH_LINK, SCHEDULED_RETRY,
     * ABANDON], so rung 0 is a debit that G12 sees as N/A on a clean case. If a
     * previous run left reauth_requested_at set with completion null, G12 blocks
     * that same rung instead -- one fewer debit attempt, and the two runs
     * disagree. A case that always converts cannot show this, which is why the
     * first version of this fixture passed while the bug was live.
     */
    public UUID reauthCase(Instant firstFailedAt, long amountPaise) {
        UUID caseId = newCase(firstFailedAt, amountPaise, firstFailedAt.plusSeconds(30 * 86400),
                true, "REAUTH_REQUIRED", 1.0);
        jdbc.sql("""
                update recovery_case set diagnosed_cause = 'AUTHENTICATION_FAILED',
                       raw_error_reason = 'payment_authentication_failed'
                 where id = :id
                """).param("id", caseId).update();
        jdbc.sql("""
                update case_oracle set true_cause = 'AUTHENTICATION_FAILED',
                       nudge_sensitivity = 0.0
                 where case_id = :id
                """).param("id", caseId).update();
        return caseId;
    }

    public UUID newCase(Instant firstFailedAt, long amountPaise, Instant billingCycleEnd,
                 boolean recoverable, String requiredChannel, double successProbability) {
        UUID customerId = jdbc.sql("""
                insert into merchant_customer (external_ref) values (:ref) returning id
                """)
                .param("ref", "test-" + UUID.randomUUID())
                .query(UUID.class).single();

        UUID mandateId = jdbc.sql("""
                insert into mandate (customer_id, rail, valid_from, valid_until,
                                     max_amount_paise, status, alternate_rail)
                values (:customerId, 'UPI_AUTOPAY', :from, :until, 100000000, 'ACTIVE', 'CARD')
                returning id
                """)
                .param("customerId", customerId)
                .param("from", firstFailedAt.minusSeconds(86400).atOffset(ZoneOffset.UTC))
                .param("until", firstFailedAt.plusSeconds(365 * 86400).atOffset(ZoneOffset.UTC))
                .query(UUID.class).single();

        UUID caseId = jdbc.sql("""
                insert into recovery_case (mandate_id, customer_id, amount_paise, billing_cycle_end,
                                           first_failed_at, status, raw_error_code, raw_error_reason,
                                           diagnosed_cause, diagnosis_confidence, diagnosis_method,
                                           batch_label, created_at, updated_at)
                values (:mandateId, :customerId, :amount, :cycleEnd, :firstFailed, 'SCHEDULED',
                        'BAD_REQUEST_ERROR', 'payment_failed_insufficient_funds',
                        'INSUFFICIENT_FUNDS', 0.95, 'CODE_MAP', :label, :firstFailed, :firstFailed)
                returning id
                """)
                .param("mandateId", mandateId)
                .param("customerId", customerId)
                .param("amount", amountPaise)
                .param("cycleEnd", billingCycleEnd.atOffset(ZoneOffset.UTC))
                .param("firstFailed", firstFailedAt.atOffset(ZoneOffset.UTC))
                .param("label", LABEL)
                .query(UUID.class).single();

        jdbc.sql("""
                insert into case_oracle (case_id, recoverable, recovery_window_start,
                                         required_channel, nudge_sensitivity,
                                         attempt_success_prob, true_cause)
                values (:caseId, :recoverable, null, :channel, 1.0, :prob, 'INSUFFICIENT_FUNDS')
                """)
                .param("caseId", caseId)
                .param("recoverable", recoverable)
                .param("channel", requiredChannel)
                .param("prob", successProbability)
                .update();

        return caseId;
    }

    public UUID intervention(UUID caseId, int attemptNo, InterventionKind kind, Instant scheduledFor) {
        return jdbc.sql("""
                insert into intervention (case_id, attempt_no, kind, scheduled_for, decision_json)
                values (:caseId, :attemptNo, :kind, :scheduledFor,
                        cast(:json as jsonb))
                returning id
                """)
                .param("caseId", caseId)
                .param("attemptNo", attemptNo)
                .param("kind", kind.name())
                .param("scheduledFor", scheduledFor.atOffset(ZoneOffset.UTC))
                .param("json", """
                        {"finalAction":"%s","customerMessage":{"text":"test copy","fromTemplate":true}}"""
                        .formatted(kind.name()))
                .query(UUID.class).single();
    }

    /** Makes G1 (opt-out) fire on any comms action for this case. */
    public void optOut(UUID caseId) {
        jdbc.sql("""
                update merchant_customer set contact_opted_out = true
                 where id = (select customer_id from recovery_case where id = :id)
                """).param("id", caseId).update();
    }

    public String caseStatus(UUID caseId) {
        return jdbc.sql("select status from recovery_case where id = :id")
                .param("id", caseId).query(String.class).single();
    }

    public String interventionOutcome(UUID interventionId) {
        return jdbc.sql("select outcome from intervention where id = :id")
                .param("id", interventionId).query(String.class).optional().orElse(null);
    }

    /**
     * Any test that calls {@code Dispatcher.dispatchDue} must pass a {@code now}
     * <b>earlier</b> than {@code batch_v1}'s earliest {@code scheduled_for}. The
     * dispatcher query is global -- it has no batch filter, and it should not
     * grow one for test convenience -- so a future {@code now} sweeps all 300
     * loaded interventions into the outbox and publishes them. 2026-08-01 is
     * safely before the batch.
     */
    public static void clean(JdbcClient jdbc) {
        // Belt and braces for the above: no real dispatch can stamp an outbox row
        // with a future creation time, so anything that has one came from a test.
        jdbc.sql("delete from outbox where created_at > now()").update();

        // The ledger is append-only by database trigger, so removing test rows
        // means switching the guard off -- the same thing the demo tamper
        // endpoint has to do, and the reason that endpoint is a two-beat demo
        // rather than one. Re-enabled in a finally, because leaving the ledger
        // mutable would be far worse than a failed cleanup.
        jdbc.sql("alter table audit_event disable trigger trg_audit_no_update").update();
        try {
            jdbc.sql("""
                    delete from audit_event where case_id in
                      (select id from recovery_case where batch_label = :label)
                    """).param("label", LABEL).update();
        } finally {
            jdbc.sql("alter table audit_event enable trigger trg_audit_no_update").update();
        }
        jdbc.sql("""
                delete from payment_attempt where case_id in
                  (select id from recovery_case where batch_label = :label)
                """).param("label", LABEL).update();
        jdbc.sql("""
                delete from outbox where aggregate_id in
                  (select i.id from intervention i join recovery_case c on c.id = i.case_id
                    where c.batch_label = :label)
                """).param("label", LABEL).update();
        jdbc.sql("""
                delete from intervention where case_id in
                  (select id from recovery_case where batch_label = :label)
                """).param("label", LABEL).update();
        jdbc.sql("delete from recovery_case where batch_label = :label")
                .param("label", LABEL).update();
        jdbc.sql("""
                delete from mandate where customer_id in
                  (select id from merchant_customer where external_ref like 'test-%')
                """).update();
        jdbc.sql("delete from merchant_customer where external_ref like 'test-%'").update();
    }
}

package dev.capstan.execute;

import dev.capstan.policy.InterventionKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every transactional database operation the execution path performs.
 *
 * <p>It is a separate bean from {@link DebitWorker} and {@link Reconciler} on
 * purpose. Spring's {@code @Transactional} is proxy-based, so a transactional
 * method called from inside the same object runs with no transaction at all and
 * says nothing about it. Keeping the writes here means every one of them is
 * reached through the proxy.
 */
@Service
@RequiredArgsConstructor
public class ExecutionStore {

    /** States in which a previous attempt's outcome is known. Nothing else. */
    private static final List<String> RECONCILED_STATES = List.of("SUCCEEDED", "FAILED");

    private final JdbcClient jdbc;

    public record Job(UUID interventionId, UUID caseId, InterventionKind kind, String caseStatus,
                      long amountPaise, String rail, String alternateRail, String locale,
                      String messageText) {
    }

    public record Attempt(UUID id, UUID caseId, String idempotencyKey, String state,
                          Instant initiatedAt, int reconcileAttempts) {
    }

    /** Raised when a debit is asked for before its predecessor's outcome is known. */
    public static class PredecessorUnreconciled extends RuntimeException {
        public PredecessorUnreconciled(UUID caseId, String state) {
            super("case " + caseId + " has an attempt in state " + state
                    + "; no debit may follow an unreconciled attempt");
        }
    }

    /**
     * Claims an intervention for execution and loads what is needed to run it.
     *
     * <p>Claiming and marking are one statement, deliberately. If the worker set
     * {@code executed_at} only after calling the gateway, there would be a
     * window in which a concurrent cancellation
     * ({@code WHERE executed_at IS NULL}) reported success while the debit was
     * already in flight. Collapsing the two removes the window instead of
     * narrowing it.
     *
     * <p>The {@code NOT EXISTS} clause keeps a second intervention off a case
     * that already has one claimed; {@code uq_case_in_flight} catches the race
     * two concurrent transactions can create that the clause cannot see, which
     * is why {@link DuplicateKeyException} is also "not claimed" rather than an
     * error.
     */
    @Transactional
    public Optional<Job> claim(UUID interventionId, Instant now, Duration lease) {
        int claimed;
        try {
            claimed = jdbc.sql("""
                    update intervention
                       set executed_at = :now, expires_at = :lease
                     where id = :id
                       and executed_at is null
                       and outcome is null
                       and not exists (select 1 from intervention other
                                        where other.case_id = intervention.case_id
                                          and other.expires_at is not null)
                    """)
                    .param("id", interventionId)
                    .param("now", at(now))
                    .param("lease", at(now.plus(lease)))
                    .update();
        } catch (DuplicateKeyException alreadyInFlight) {
            return Optional.empty();
        }
        if (claimed == 0) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select i.id, i.case_id, i.kind, c.status, c.amount_paise,
                       m.rail, m.alternate_rail, cu.preferred_locale,
                       i.decision_json->'customerMessage'->>'text' as message_text
                  from intervention i
                  join recovery_case c on c.id = i.case_id
                  join mandate m on m.id = c.mandate_id
                  join merchant_customer cu on cu.id = c.customer_id
                 where i.id = :id
                """)
                .param("id", interventionId)
                .query((ResultSet rs, int rowNum) -> new Job(
                        rs.getObject("id", UUID.class),
                        rs.getObject("case_id", UUID.class),
                        InterventionKind.valueOf(rs.getString("kind")),
                        rs.getString("status"),
                        rs.getLong("amount_paise"),
                        rs.getString("rail"),
                        rs.getString("alternate_rail"),
                        rs.getString("preferred_locale"),
                        rs.getString("message_text")))
                .optional();
    }

    /** Nothing ran: drop the claim entirely so the row reads as never executed. */
    @Transactional
    public void abandonClaim(UUID interventionId, String outcome, String reason) {
        jdbc.sql("""
                update intervention
                   set executed_at = null, expires_at = null,
                       outcome = :outcome, cancel_reason = :reason
                 where id = :id
                """)
                .param("id", interventionId)
                .param("outcome", outcome)
                .param("reason", reason)
                .update();
    }

    /** Something ran: keep executed_at, drop the lease, record the outcome. */
    @Transactional
    public void settleClaim(UUID interventionId, String outcome) {
        jdbc.sql("update intervention set expires_at = null, outcome = :outcome where id = :id")
                .param("id", interventionId)
                .param("outcome", outcome)
                .update();
    }

    /** Releases the lease without settling, so the row can be re-dispatched. */
    @Transactional
    public void releaseLease(UUID interventionId) {
        jdbc.sql("update intervention set expires_at = null, executed_at = null where id = :id")
                .param("id", interventionId)
                .update();
    }

    /**
     * <b>The invariant.</b> Capstan never initiates a debit whose predecessor's
     * outcome has not been reconciled.
     *
     * <p>This cannot come from the policy ladders -- only NETWORK_TIMEOUT has a
     * {@code RECONCILE_ONLY} rung, and five other ladders step straight from one
     * debit to the next. It has to be a precondition on the one code path that
     * debits, which is what this is.
     *
     * <p>The check is affirmative on purpose. Written as "not INITIATED" it
     * would let {@code UNKNOWN} through, and letting an unknown attempt
     * authorise the next debit is exactly how double charges happen. Anything
     * that is not positively known to be finished blocks.
     */
    public void requirePredecessorReconciled(UUID caseId) {
        Optional<String> newest = jdbc.sql("""
                select state from payment_attempt
                 where case_id = :id
                 order by debit_seq desc
                 limit 1
                """)
                .param("id", caseId)
                .query(String.class)
                .optional();

        if (newest.isPresent() && !RECONCILED_STATES.contains(newest.get())) {
            throw new PredecessorUnreconciled(caseId, newest.get());
        }
    }

    @Transactional
    public int nextDebitSeq(UUID caseId) {
        return jdbc.sql("select coalesce(max(debit_seq), 0) + 1 from payment_attempt where case_id = :id")
                .param("id", caseId)
                .query(Integer.class)
                .single();
    }

    /**
     * Writes the attempt before the gateway is called, in its own transaction.
     * If the process dies between this commit and the response, recovery finds
     * an {@code INITIATED} row and reconciles it rather than guessing.
     */
    @Transactional
    public UUID insertAttempt(UUID caseId, UUID interventionId, int debitSeq, String key,
                              String rail, long amountPaise, Instant now, Instant reconcileAfter) {
        return jdbc.sql("""
                insert into payment_attempt (case_id, intervention_id, debit_seq, idempotency_key,
                                             rail, amount_paise, state, initiated_at, next_reconcile_at)
                values (:caseId, :interventionId, :debitSeq, :key, :rail, :amount,
                        'INITIATED', :now, :reconcileAfter)
                returning id
                """)
                .param("caseId", caseId)
                .param("interventionId", interventionId)
                .param("debitSeq", debitSeq)
                .param("key", key)
                .param("rail", rail)
                .param("amount", amountPaise)
                .param("now", at(now))
                .param("reconcileAfter", at(reconcileAfter))
                .query(UUID.class)
                .single();
    }

    @Transactional
    public void markAttempt(UUID attemptId, String state, String gatewayRef, String failureReason,
                            Instant settledAt, Instant reconciledAt, Instant nextReconcileAt) {
        jdbc.sql("""
                update payment_attempt
                   set state = :state,
                       gateway_ref = coalesce(:ref, gateway_ref),
                       failure_reason = :reason,
                       settled_at = :settledAt,
                       reconciled_at = coalesce(:reconciledAt, reconciled_at),
                       next_reconcile_at = :nextReconcileAt,
                       reconcile_attempts = reconcile_attempts
                                            + case when :counts then 1 else 0 end
                 where id = :id
                """)
                .param("id", attemptId)
                .param("state", state)
                .param("ref", gatewayRef)
                .param("reason", failureReason)
                .param("settledAt", at(settledAt))
                .param("reconciledAt", at(reconciledAt))
                .param("nextReconcileAt", at(nextReconcileAt))
                .param("counts", nextReconcileAt != null)
                .update();
    }

    @Transactional
    public void recordReauth(UUID caseId, Instant requestedAt, Instant completedAt) {
        jdbc.sql("""
                update recovery_case
                   set reauth_requested_at = :requested, reauth_completed_at = :completed
                 where id = :id
                """)
                .param("id", caseId)
                .param("requested", at(requestedAt))
                .param("completed", at(completedAt))
                .update();
    }

    @Transactional
    public void setCaseStatus(UUID caseId, String status, Instant now) {
        jdbc.sql("update recovery_case set status = :status, updated_at = :now where id = :id")
                .param("id", caseId)
                .param("status", status)
                .param("now", at(now))
                .update();
    }

    @Transactional
    public void terminateCase(UUID caseId, String status, String reason, Instant now) {
        jdbc.sql("""
                update recovery_case
                   set status = :status, terminal_reason = :reason, updated_at = :now
                 where id = :id
                """)
                .param("id", caseId)
                .param("status", status)
                .param("reason", reason)
                .param("now", at(now))
                .update();
    }

    /**
     * Cancels work that a resolved outcome has made pointless. Conditional on
     * {@code executed_at IS NULL} so it can never cancel something already
     * claimed -- the claim sets that column in the same statement that takes the
     * row.
     */
    @Transactional
    public int cancelQueued(UUID caseId, UUID except, String reason) {
        return jdbc.sql("""
                update intervention
                   set outcome = 'CANCELLED', cancel_reason = :reason
                 where case_id = :caseId
                   and id is distinct from cast(:except as uuid)
                   and outcome is null
                   and executed_at is null
                """)
                .param("caseId", caseId)
                .param("except", except)
                .param("reason", reason)
                .update();
    }

    /** Re-read, for callers that must not overwrite a status something else set. */
    public String caseStatus(UUID caseId) {
        return jdbc.sql("select status from recovery_case where id = :id")
                .param("id", caseId).query(String.class).single();
    }

    public Optional<Attempt> attempt(UUID attemptId) {
        return jdbc.sql("""
                select id, case_id, idempotency_key, state, initiated_at, reconcile_attempts
                  from payment_attempt where id = :id
                """)
                .param("id", attemptId)
                .query(ExecutionStore::mapAttempt)
                .optional();
    }

    public Optional<Attempt> attemptByKey(String key) {
        return jdbc.sql("""
                select id, case_id, idempotency_key, state, initiated_at, reconcile_attempts
                  from payment_attempt where idempotency_key = :key
                """)
                .param("key", key)
                .query(ExecutionStore::mapAttempt)
                .optional();
    }

    /** Every attempt on one case whose outcome is still not known. */
    public List<UUID> unresolvedFor(UUID caseId) {
        return jdbc.sql("""
                select id from payment_attempt
                 where case_id = :id
                   and state in ('INITIATED', 'UNKNOWN')
                   and reconciled_at is null
                 order by debit_seq
                """)
                .param("id", caseId)
                .query(UUID.class)
                .list();
    }

    /** Unresolved attempts whose backoff has elapsed, oldest first. */
    public List<UUID> dueForReconcile(Instant now, int limit) {
        return jdbc.sql("""
                select id from payment_attempt
                 where state in ('INITIATED', 'UNKNOWN')
                   and reconciled_at is null
                   and (next_reconcile_at is null or next_reconcile_at <= :now)
                 order by initiated_at
                 limit :limit
                """)
                .param("now", at(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    private static Attempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime initiated = rs.getObject("initiated_at", OffsetDateTime.class);
        return new Attempt(
                rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("state"),
                initiated == null ? null : initiated.toInstant(),
                rs.getInt("reconcile_attempts"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

package dev.capstan.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.capstan.audit.AuditLedger;
import dev.capstan.execute.ExecutionStore.Job;
import dev.capstan.execute.ExecutionStore.PredecessorUnreconciled;
import dev.capstan.gateway.IdempotencyKey;
import dev.capstan.gateway.PaymentGateway;
import dev.capstan.gateway.PaymentGateway.AttemptResult;
import dev.capstan.gateway.PaymentGateway.CommsCommand;
import dev.capstan.gateway.PaymentGateway.DebitCommand;
import dev.capstan.policy.InterventionKind;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Executes one authorised intervention.
 *
 * <p>Four things happen here in a fixed order, and the order is the safety
 * property: claim the row, re-read the case, check the predecessor, only then
 * debit.
 */
@Component
@RequiredArgsConstructor
public class DebitWorker {

    private static final Logger log = LoggerFactory.getLogger(DebitWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How long a claim holds the case's in-flight slot before it can be re-driven. */
    private static final Duration LEASE = Duration.ofMinutes(10);
    /** First reconciliation of an attempt whose outcome never arrived. */
    private static final Duration FIRST_RECONCILE = Duration.ofSeconds(30);
    /** How long a customer takes to act on a re-auth link, once they will. */
    private static final Duration REAUTH_RESPONSE = Duration.ofHours(6);

    /** Case states in which acting is still meaningful. Nothing else is. */
    private static final List<String> LIVE = List.of("SCHEDULED", "IN_FLIGHT");

    private final ExecutionStore store;
    private final AuditLedger ledger;
    private final PaymentGateway gateway;
    private final Reconciler reconciler;
    private final Clock clock;

    /**
     * The outbox publishes at-least-once: it sends and then marks, so a crash
     * between the two redelivers this message. That is safe <b>only</b> because
     * this consumer is idempotent -- {@code uq_idem} rejects a second
     * {@code payment_attempt} for the same debit, and {@link ExecutionStore#claim}
     * returns empty for a row that is already claimed or settled. If either of
     * those goes away, at-least-once delivery becomes at-least-once <i>debiting</i>.
     */
    @RabbitListener(queues = {RabbitConfig.DEBIT_QUEUE, RabbitConfig.COMMS_QUEUE})
    public void onMessage(String body, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        boolean ack;
        try {
            UUID interventionId = UUID.fromString(
                    JSON.readTree(body).path("interventionId").asText());
            ack = execute(interventionId, Instant.now(clock));
        } catch (Exception e) {
            log.error("Execution failed for message {}", body, e);
            ack = false;
        }
        if (ack) {
            channel.basicAck(deliveryTag, false);
        } else {
            // Never requeue. default-requeue-rejected: false routes this to the
            // DLX, where a human or the replay endpoint decides. A requeue would
            // put the message straight back in front of the same gateway.
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * @return true to acknowledge, false to dead-letter
     */
    public boolean execute(UUID interventionId, Instant now) {
        Optional<Job> claimed = store.claim(interventionId, now, LEASE);
        if (claimed.isEmpty()) {
            // Cancelled, already claimed, already settled, or another
            // intervention holds this case's in-flight slot. All of those mean
            // it is not ours to run, and none of them is an error.
            log.debug("Intervention {} not claimable", interventionId);
            return true;
        }
        Job job = claimed.get();

        // The decision that authorised this may be minutes old, and the case can
        // have terminated since -- Phase 04 writes intervention rows ahead of
        // execution, so a stale authorisation is normal rather than exceptional.
        if (!LIVE.contains(job.caseStatus())) {
            store.abandonClaim(interventionId, "CANCELLED",
                    "case_" + job.caseStatus().toLowerCase() + "_before_execution");
            ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE,
                    AuditLedger.INTERVENTION_CANCELLED,
                    Map.of("interventionId", interventionId.toString(),
                            "kind", job.kind().name(),
                            "reason", "case " + job.caseStatus() + " before execution"), now);
            log.info("Skipped {} on case {}: status is {}",
                    job.kind(), job.caseId(), job.caseStatus());
            return true;
        }

        if (job.kind().isComms) {
            return sendComms(job, now);
        }
        if (job.kind() == InterventionKind.RECONCILE_ONLY) {
            return reconcileOnly(job, now);
        }
        if (!job.kind().isDebit) {
            store.abandonClaim(interventionId, "SKIPPED", "not an executable action");
            return true;
        }
        return debit(job, now);
    }

    private boolean debit(Job job, Instant now) {
        // The invariant, on the one path that debits.
        try {
            store.requirePredecessorReconciled(job.caseId());
        } catch (PredecessorUnreconciled blocked) {
            // Drop the claim without settling, so this intervention can run once
            // the reconciler has resolved what came before it.
            store.releaseLease(job.interventionId());
            ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_BLOCKED,
                    Map.of("interventionId", job.interventionId().toString(),
                            "reason", blocked.getMessage()), now);
            log.warn("Debit blocked on case {}: {}", job.caseId(), blocked.getMessage());
            return true;
        }

        boolean alternateRail = job.kind() == InterventionKind.RAIL_SWITCH
                && job.alternateRail() != null;
        String rail = alternateRail ? job.alternateRail() : job.rail();
        int debitSeq = store.nextDebitSeq(job.caseId());
        String key = IdempotencyKey.idempotencyKey(job.caseId(), debitSeq, rail, job.amountPaise());

        UUID attemptId;
        try {
            attemptId = store.insertAttempt(job.caseId(), job.interventionId(), debitSeq, key,
                    rail, job.amountPaise(), now, now.plus(FIRST_RECONCILE));
        } catch (DuplicateKeyException existing) {
            return adoptExistingAttempt(job, key, now);
        }

        store.setCaseStatus(job.caseId(), "IN_FLIGHT", now);
        ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_INITIATED,
                Map.of("attemptId", attemptId.toString(), "debitSeq", debitSeq,
                        "rail", rail, "amountPaise", job.amountPaise(),
                        "idempotencyKey", key), now);

        AttemptResult result = gateway.debit(new DebitCommand(
                job.caseId(), job.interventionId(), key, rail, alternateRail,
                job.amountPaise(), now));

        return switch (result.outcome()) {
            case SUCCEEDED -> {
                store.markAttempt(attemptId, "SUCCEEDED", result.gatewayRef(), null, now, null, null);
                store.settleClaim(job.interventionId(), "SUCCEEDED");
                store.terminateCase(job.caseId(), "RECOVERED",
                        "Recovered on debit attempt " + debitSeq, now);
                store.cancelQueued(job.caseId(), job.interventionId(), "superseded_by_recovery");
                ledger.record(job.caseId(), AuditLedger.ACTOR_GATEWAY, AuditLedger.GATEWAY_RESPONSE,
                        Map.of("attemptId", attemptId.toString(), "state", "SUCCEEDED",
                                "gatewayRef", String.valueOf(result.gatewayRef()),
                                "amountPaise", job.amountPaise()), now);
                ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.CASE_TERMINATED,
                        Map.of("status", "RECOVERED",
                                "terminalReason", "Recovered on debit attempt " + debitSeq,
                                "recoveredPaise", job.amountPaise()), now);
                yield true;
            }
            case FAILED -> {
                store.markAttempt(attemptId, "FAILED", result.gatewayRef(), result.reason(),
                        now, null, null);
                store.settleClaim(job.interventionId(), "FAILED");
                // Back to awaiting a decision. The ladder advances on the next
                // decide(), not here -- this worker chooses nothing. Guarded, so
                // a case the reconciler terminated while this debit was in flight
                // is not quietly resurrected.
                if (LIVE.contains(store.caseStatus(job.caseId()))) {
                    store.setCaseStatus(job.caseId(), "DIAGNOSED", now);
                }
                ledger.record(job.caseId(), AuditLedger.ACTOR_GATEWAY, AuditLedger.GATEWAY_RESPONSE,
                        Map.of("attemptId", attemptId.toString(), "state", "FAILED",
                                "gatewayRef", String.valueOf(result.gatewayRef()),
                                "reason", String.valueOf(result.reason())), now);
                yield true;
            }
            case UNKNOWN -> {
                // Money may already have moved. The case stays IN_FLIGHT, which
                // keeps the dispatcher off it, and the reconciler owns it now.
                store.markAttempt(attemptId, "UNKNOWN", null, result.reason(),
                        null, null, now.plus(FIRST_RECONCILE));
                store.settleClaim(job.interventionId(), "UNKNOWN");
                ledger.record(job.caseId(), AuditLedger.ACTOR_GATEWAY, AuditLedger.ATTEMPT_UNKNOWN,
                        Map.of("attemptId", attemptId.toString(),
                                "reason", String.valueOf(result.reason()),
                                "idempotencyKey", key,
                                "reconcileBudget", Reconciler.MAX_ATTEMPTS), now);
                log.warn("Debit outcome unknown for case {} attempt {}", job.caseId(), attemptId);
                yield false;
            }
        };
    }

    /**
     * A {@code uq_idem} conflict is information, not a failure: this exact debit
     * already exists. Treating it as a transient error would nack the message
     * and retry forever against an attempt that is already recorded.
     */
    private boolean adoptExistingAttempt(Job job, String key, Instant now) {
        Optional<ExecutionStore.Attempt> found = store.attemptByKey(key);
        if (found.isEmpty()) {
            // The conflict was uq_case_debit_seq under a different key, so
            // another worker is mid-flight on this case. Leave it to them.
            store.releaseLease(job.interventionId());
            return true;
        }
        ExecutionStore.Attempt attempt = found.get();
        ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.DEBIT_BLOCKED,
                Map.of("attemptId", attempt.id().toString(), "state", attempt.state(),
                        "idempotencyKey", key,
                        "reason", "this debit is already recorded; not attempting it again"), now);

        if (List.of("SUCCEEDED", "FAILED").contains(attempt.state())) {
            store.settleClaim(job.interventionId(), attempt.state());
        } else {
            // Unresolved. Not ours to debit again; the reconciler resolves it.
            store.releaseLease(job.interventionId());
        }
        return true;
    }

    private boolean sendComms(Job job, Instant now) {
        gateway.sendCommunication(new CommsCommand(
                job.caseId(), job.kind().name(), job.locale(), job.messageText(), now));
        if (job.kind() == InterventionKind.REAUTH_LINK) {
            // Record what we asked for and, if the customer acted, that they did.
            // G12 reads both: a debit waits for completion, never for the ask.
            store.recordReauth(job.caseId(), now,
                    gateway.reauthCompleted(job.caseId()) ? now.plus(REAUTH_RESPONSE) : null);
        }
        store.settleClaim(job.interventionId(), "SENT");
        if (LIVE.contains(store.caseStatus(job.caseId()))) {
            store.setCaseStatus(job.caseId(), "DIAGNOSED", now);
        }
        ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.COMMS_SENT,
                Map.of("interventionId", job.interventionId().toString(),
                        "kind", job.kind().name(),
                        "locale", String.valueOf(job.locale()),
                        "fromTemplate", true), now);
        return true;
    }

    private boolean reconcileOnly(Job job, Instant now) {
        int resolved = reconciler.reconcileCase(job.caseId(), now);
        store.settleClaim(job.interventionId(), "RECONCILED");
        // Same rule as comms: a settled non-terminal intervention leaves the case
        // awaiting a decision. Left in SCHEDULED it would sit forever -- the
        // dispatcher skips settled rows, so nothing would ever pick it up again.
        // The reconciler owns any case it moved to RECOVERED or ESCALATED, so
        // only overwrite a status that is still live.
        if (LIVE.contains(store.caseStatus(job.caseId()))) {
            store.setCaseStatus(job.caseId(), "DIAGNOSED", now);
        }
        ledger.record(job.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.RECONCILE_ATTEMPTED,
                Map.of("interventionId", job.interventionId().toString(),
                        "resolvedState", resolved > 0 ? "resolved " + resolved + " attempt(s)"
                                : "nothing outstanding"), now);
        return true;
    }
}

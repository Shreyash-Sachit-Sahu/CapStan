package dev.capstan.execute;

import dev.capstan.audit.AuditLedger;
import dev.capstan.execute.ExecutionStore.Attempt;
import dev.capstan.gateway.PaymentGateway;
import dev.capstan.gateway.PaymentGateway.AttemptResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves attempts whose outcome never arrived.
 *
 * <p>Takes {@code now} as a parameter and schedules nothing. In live mode
 * {@link ExecutionScheduler} passes wall-clock time; in the backtest Phase 07's
 * tick loop passes virtual time. That split is not cosmetic: the budget below is
 * expressed as durations against {@code initiated_at}, and if the reconciler
 * read wall time while attempts were stamped with virtual time, every backoff
 * comparison would be meaningless and injected timeouts would never recover.
 */
@Service
@RequiredArgsConstructor
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    /** Five attempts, and a hard deadline, whichever binds first. */
    static final int MAX_ATTEMPTS = 5;
    static final Duration DEADLINE = Duration.ofMinutes(30);
    static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private static final List<String> UNRESOLVED = List.of("INITIATED", "UNKNOWN");

    private final ExecutionStore store;
    private final AuditLedger ledger;
    private final PaymentGateway gateway;

    /** @return how many attempts were resolved to a definite outcome */
    public int runDue(Instant now) {
        int resolved = 0;
        for (UUID attemptId : store.dueForReconcile(now, 200)) {
            if (reconcile(attemptId, now)) {
                resolved++;
            }
        }
        return resolved;
    }

    /** Reconciles every unresolved attempt on one case. */
    public int reconcileCase(UUID caseId, Instant now) {
        int resolved = 0;
        for (UUID attemptId : store.unresolvedFor(caseId)) {
            if (reconcile(attemptId, now)) {
                resolved++;
            }
        }
        return resolved;
    }

    boolean reconcile(UUID attemptId, Instant now) {
        Optional<Attempt> found = store.attempt(attemptId);
        if (found.isEmpty() || !UNRESOLVED.contains(found.get().state())) {
            return false;
        }
        Attempt attempt = found.get();

        if (exhausted(attempt, now)) {
            escalate(attempt, now);
            return false;
        }

        AttemptResult result = gateway.fetchByIdempotencyKey(attempt.idempotencyKey());
        return switch (result.outcome()) {
            case SUCCEEDED -> {
                store.markAttempt(attemptId, "SUCCEEDED", result.gatewayRef(), null, now, now, null);
                store.terminateCase(attempt.caseId(), "RECOVERED",
                        "Recovered: attempt reconciled to success after an unknown outcome", now);
                int cancelled = store.cancelQueued(attempt.caseId(), null,
                        "superseded_by_reconciliation");
                ledger.record(attempt.caseId(), AuditLedger.ACTOR_GATEWAY,
                        AuditLedger.RECONCILE_ATTEMPTED,
                        Map.of("attemptId", attemptId.toString(),
                                "idempotencyKey", attempt.idempotencyKey(),
                                "resolvedState", "SUCCEEDED",
                                "gatewayRef", String.valueOf(result.gatewayRef()),
                                "cancelledInterventions", cancelled), now);
                ledger.record(attempt.caseId(), AuditLedger.ACTOR_EXECUTE,
                        AuditLedger.CASE_TERMINATED,
                        Map.of("status", "RECOVERED",
                                "terminalReason", "Attempt reconciled to success after an "
                                        + "unknown outcome"), now);
                log.info("Reconciled attempt {} to SUCCEEDED; cancelled {} queued interventions",
                        attemptId, cancelled);
                yield true;
            }
            case FAILED -> {
                store.markAttempt(attemptId, "FAILED", result.gatewayRef(), result.reason(),
                        null, now, null);
                store.setCaseStatus(attempt.caseId(), "DIAGNOSED", now);
                ledger.record(attempt.caseId(), AuditLedger.ACTOR_GATEWAY,
                        AuditLedger.RECONCILE_ATTEMPTED,
                        Map.of("attemptId", attemptId.toString(),
                                "idempotencyKey", attempt.idempotencyKey(),
                                "resolvedState", "FAILED",
                                "reason", String.valueOf(result.reason())), now);
                yield true;
            }
            case UNKNOWN -> {
                int next = attempt.reconcileAttempts() + 1;
                store.markAttempt(attemptId, attempt.state(), null, result.reason(),
                        null, null, now.plus(backoff(next)));
                log.debug("Attempt {} still unknown after {} reconcile attempts", attemptId, next);
                yield false;
            }
        };
    }

    /**
     * Escalates to a human. <b>Never FAILED.</b> FAILED is a permission -- it is
     * what authorises the next debit -- and an attempt we could not resolve has
     * granted no such thing. The attempt stays UNKNOWN, which is the truth; it
     * is the case that escalates.
     */
    private void escalate(Attempt attempt, Instant now) {
        // reconciled_at means "reconciliation concluded", not "concluded well".
        // Without it the attempt stays UNKNOWN with a null next_reconcile_at and
        // matches the due query forever, re-escalating on every pass.
        store.markAttempt(attempt.id(), attempt.state(), null,
                "reconcile budget exhausted", null, now, null);
        store.terminateCase(attempt.caseId(), "ESCALATED",
                "Payment attempt outcome unresolved after " + attempt.reconcileAttempts()
                        + " reconciliation attempts; a human must confirm whether the customer was charged",
                now);
        ledger.record(attempt.caseId(), AuditLedger.ACTOR_EXECUTE,
                AuditLedger.RECONCILE_ATTEMPTED,
                Map.of("attemptId", attempt.id().toString(),
                        "idempotencyKey", attempt.idempotencyKey(),
                        "resolvedState", "still unknown, budget exhausted",
                        "reconcileAttempts", attempt.reconcileAttempts()), now);
        ledger.record(attempt.caseId(), AuditLedger.ACTOR_EXECUTE, AuditLedger.CASE_TERMINATED,
                Map.of("status", "ESCALATED",
                        "terminalReason", "Payment attempt outcome unresolved; a human must "
                                + "confirm whether the customer was charged"), now);
        log.error("Reconcile budget exhausted for attempt {} on case {}; escalating",
                attempt.id(), attempt.caseId());
    }

    /**
     * The deadline only binds once at least one lookup has been spent. A first
     * visit that arrives late -- which the backtest's 15-minute ticks make
     * ordinary -- should still get to ask. Escalating without ever asking would
     * spend none of the budget the escalation claims to have exhausted.
     */
    private static boolean exhausted(Attempt attempt, Instant now) {
        if (attempt.reconcileAttempts() >= MAX_ATTEMPTS) {
            return true;
        }
        return attempt.reconcileAttempts() > 0
                && attempt.initiatedAt() != null
                && now.isAfter(attempt.initiatedAt().plus(DEADLINE));
    }

    /** 30s, 1m, 2m, 4m, 8m -- the last well inside the 30-minute deadline. */
    static Duration backoff(int attemptNumber) {
        return BASE_BACKOFF.multipliedBy(1L << Math.max(0, attemptNumber - 1));
    }
}

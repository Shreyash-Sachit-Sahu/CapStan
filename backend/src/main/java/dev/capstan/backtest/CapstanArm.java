package dev.capstan.backtest;

import dev.capstan.execute.DebitWorker;
import dev.capstan.execute.Reconciler;
import dev.capstan.policy.DecisionContext;
import dev.capstan.policy.DecisionRecord;
import dev.capstan.policy.DecisionService;
import dev.capstan.policy.InterventionKind;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Capstan itself, driven tick by tick through the real pipeline: the real
 * {@link DecisionService}, the real {@link DebitWorker}, the real
 * {@link Reconciler}, the real guardrails and the real database rows.
 *
 * <p>One deliberate omission: the outbox and RabbitMQ are skipped and the worker
 * is invoked directly. The queue is an execution-transport concern that Phase
 * 05's tests already cover; routing 3,360 ticks through a broker would make the
 * backtest slow and its ordering non-deterministic, which would cost
 * reproducibility to test something this phase is not measuring.
 */
@Service
@RequiredArgsConstructor
public class CapstanArm implements BacktestArm {

    private static final Logger log = LoggerFactory.getLogger(CapstanArm.class);
    /** What a system without salary-cycle inference would do instead. */
    private static final Duration FLAT_RETRY_DELAY = Duration.ofHours(48);

    private final JdbcClient jdbc;
    private final DecisionService decisions;
    private final DebitWorker worker;
    private final Reconciler reconciler;

    @Override
    public String name() {
        return "capstan";
    }

    @Override
    public void run(RunContext ctx) {
        VirtualClock clock = ctx.clock();
        Ablation ablation = ctx.ablation();

        while (clock.hasNext()) {
            Instant now = clock.now();

            for (UUID caseId : awaitingDecision(ctx.batchLabel(), now)) {
                decideOne(caseId, now, ablation);
            }
            for (UUID interventionId : due(ctx.batchLabel(), now)) {
                worker.execute(interventionId, now);
            }
            if (ablation.skipReconcile()) {
                // No reconciliation at all: an unknown outcome is filed as a
                // failure, which unblocks the next debit. That is the mechanism's
                // absence, and it is where duplicate charges come from.
                fileUnknownsAsFailed(ctx.batchLabel(), now);
            } else {
                reconciler.runDue(now);
            }
            clock.advance();
        }
    }

    private void decideOne(UUID caseId, Instant now, Ablation ablation) {
        DecisionContext ctx = decisions.loadContext(caseId, now).orElse(null);
        if (ctx == null) {
            return;
        }
        DecisionRecord record;
        try {
            record = decisions.decide(ablation.shape(ctx));
        } catch (RuntimeException e) {
            // A case that cannot advance its ladder any further (uq_case_attempt)
            // is finished, not broken. Park it so the tick loop stops revisiting.
            log.debug("Case {} could not be decided at {}: {}", caseId, now, e.toString());
            park(caseId, now);
            return;
        }
        if (ablation.flattenPaydaySchedule()
                && record.finalAction() == InterventionKind.PAYDAY_RETRY) {
            jdbc.sql("""
                    update intervention set scheduled_for = :at
                     where case_id = :caseId and outcome is null and executed_at is null
                    """)
                    .param("caseId", caseId)
                    .param("at", now.plus(FLAT_RETRY_DELAY).atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    private void park(UUID caseId, Instant now) {
        jdbc.sql("""
                update recovery_case
                   set status = 'ABANDONED',
                       terminal_reason = coalesce(terminal_reason, 'ladder exhausted'),
                       updated_at = :now
                 where id = :id
                """)
                .param("id", caseId)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void fileUnknownsAsFailed(String batchLabel, Instant now) {
        jdbc.sql("""
                update payment_attempt
                   set state = 'FAILED', failure_reason = 'no reconciliation (ablated)',
                       reconciled_at = :now, next_reconcile_at = null
                 where state in ('INITIATED', 'UNKNOWN')
                   and case_id in (select id from recovery_case where batch_label = :label)
                """)
                .param("label", batchLabel)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                update recovery_case set status = 'DIAGNOSED', updated_at = :now
                 where batch_label = :label and status = 'IN_FLIGHT'
                """)
                .param("label", batchLabel)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * A case enters the simulation when <b>it</b> failed, not when the batch's
     * earliest case failed.
     *
     * <p>Without the {@code first_failed_at <= now} bound, every case was decided
     * at the window's opening instant — days or weeks before its own failure. The
     * ladder then burned its retries against a balance that had not yet gone
     * short, exhausted early, and abandoned. It showed up as a mean time to
     * recovery of <i>minus</i> 9.5 hours, which is the only reason it was caught:
     * a recovery cannot precede the failure it recovers from.
     *
     * <p>The baseline never had this bug because it anchors each ladder step to
     * that case's own {@code first_failed_at}. Left in, it would have handicapped
     * Capstan by roughly 30 points of recovery rate.
     */
    private List<UUID> awaitingDecision(String batchLabel, Instant now) {
        return jdbc.sql("""
                select id from recovery_case
                 where batch_label = :label
                   and status in ('OPEN', 'DIAGNOSED')
                   and first_failed_at <= :now
                 order by id
                """)
                .param("label", batchLabel)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }

    private List<UUID> due(String batchLabel, Instant now) {
        return jdbc.sql("""
                select i.id
                  from intervention i
                  join recovery_case c on c.id = i.case_id
                 where c.batch_label = :label
                   and i.executed_at is null
                   and i.outcome is null
                   and i.scheduled_for <= :now
                   and c.status = 'SCHEDULED'
                   and i.kind not in ('ABANDON', 'HUMAN_ESCALATION')
                 order by i.scheduled_for, i.id
                """)
                .param("label", batchLabel)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }
}

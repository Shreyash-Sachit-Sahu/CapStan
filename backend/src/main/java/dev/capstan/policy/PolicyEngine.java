package dev.capstan.policy;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Chooses exactly one bounded intervention, or terminates the case.
 *
 * <p><b>Pure.</b> No database, no clock, no classifier — the same context yields
 * a byte-identical record on every run. That is what makes the backtest
 * meaningful and the property test possible, and it is why the model cannot
 * reach a decision path even by accident: this class has no way to call one.
 */
@Component
public class PolicyEngine {

    /** Low-confidence threshold below which the cause-specific ladder is ignored. */
    static final double CONFIDENCE_FLOOR = 0.60;

    /** Bounds ladder walking and substitution so a misconfigured table cannot spin. */
    private static final int MAX_RESOLUTION_STEPS = 32;

    private final PolicyTable policies;
    private final List<Guardrail> guardrails;

    public PolicyEngine(PolicyTable policies) {
        this.policies = policies;
        this.guardrails = Guardrails.all();
    }

    private record Selection(PolicyTable.Policy policy, List<DecisionRecord.ClearanceCheck> checks) {
    }

    public DecisionRecord decide(DecisionContext ctx) {
        Selection selection = selectPolicy(ctx);
        PolicyTable.Policy policy = selection.policy();

        List<DecisionRecord.GuardrailEvaluation> allEvaluations = new ArrayList<>();
        int position = Math.max(0, ctx.ladderPosition());
        InterventionKind originalProposal = position < policy.ladder().size()
                ? policy.ladder().get(position) : InterventionKind.ABANDON;

        InterventionKind proposed = null;
        InterventionKind finalAction = null;
        java.time.Instant deferUntil = null;
        String terminalStatus = null;
        String terminalReason = null;
        String substitutionReason = null;

        for (int step = 0; step < MAX_RESOLUTION_STEPS; step++) {
            if (proposed == null) {
                if (position >= policy.ladder().size()) {
                    finalAction = InterventionKind.ABANDON;
                    terminalStatus = "ABANDONED";
                    terminalReason = policy.terminalReason() != null
                            ? policy.terminalReason()
                            : "Ladder exhausted for " + policy.name() + " without a permitted action";
                    break;
                }
                proposed = policy.ladder().get(position);
            }

            List<DecisionRecord.GuardrailEvaluation> pass = evaluateAll(ctx, proposed, policy);
            allEvaluations.addAll(pass);

            Optional<Decisive> decisive = firstDecisive(ctx, proposed, policy);
            if (decisive.isEmpty()) {
                finalAction = proposed;
                break;
            }

            Guardrail.Outcome outcome = decisive.get().outcome();
            if (outcome.result() == Guardrail.Outcome.Result.DEFER) {
                finalAction = proposed;
                deferUntil = outcome.deferUntil();
                break;
            }

            switch (outcome.disposition()) {
                case ADVANCE_LADDER -> {
                    position++;
                    proposed = null;
                }
                case SUBSTITUTE -> {
                    if (outcome.substitute() == proposed) {
                        // Already the safest thing this guardrail would ask for.
                        finalAction = proposed;
                        step = MAX_RESOLUTION_STEPS;
                    } else {
                        // Re-enter the loop so the substitute is itself checked. A
                        // re-auth link substituted for an over-cap debit is comms,
                        // and must still respect opt-out and quiet hours.
                        proposed = outcome.substitute();
                        // Why we ended up here, in case the substitute is terminal.
                        // Without this a risk-hold escalation would inherit the
                        // ladder's exhaustion reason and the trail would say the
                        // balance never arrived, which is not what stopped it.
                        substitutionReason = decisive.get().guardrail().id() + " "
                                + decisive.get().guardrail().name() + ": " + outcome.detail();
                    }
                }
                case TERMINATE -> {
                    // NOT the proposed action. A terminating guardrail means nothing
                    // is done, and leaving the proposal here would write a debit into
                    // intervention.kind for Phase 05 to pick up and execute.
                    finalAction = InterventionKind.ABANDON;
                    terminalStatus = outcome.terminalStatus();
                    terminalReason = outcome.detail();
                }
            }
            if (terminalStatus != null || finalAction != null) {
                break;
            }
        }

        if (finalAction == null) {
            // Only reachable if the table is pathological; fail closed.
            finalAction = InterventionKind.HUMAN_ESCALATION;
            terminalStatus = "ESCALATED";
            terminalReason = "No permitted action could be resolved within the step bound";
        }

        if (terminalStatus == null && finalAction.isTerminal()) {
            terminalStatus = finalAction == InterventionKind.ABANDON ? "ABANDONED" : "ESCALATED";
            // A guardrail that pushed us here explains the stop better than the
            // ladder does.
            terminalReason = substitutionReason != null
                    ? substitutionReason : terminalReasonFor(finalAction, policy, ctx);
        }

        ScheduleExpression.Resolved schedule = resolveSchedule(ctx, policy, finalAction, deferUntil, position);

        return new DecisionRecord(
                deterministicId(ctx, position, finalAction),
                ctx.caseId(),
                ctx.now(),
                policies.version(),
                policy.name(),
                new DecisionRecord.Diagnosis(
                        ctx.cause().name(), ctx.diagnosisConfidence(),
                        ctx.diagnosisMethod() == null ? null : ctx.diagnosisMethod().name(),
                        ctx.diagnosisEvidence()),
                position,
                originalProposal,
                List.copyOf(allEvaluations),
                selection.checks(),
                finalAction,
                finalAction.isTerminal() ? null : schedule.at(),
                finalAction.isTerminal() ? null : schedule.rationale(),
                ctx.amountPaise(),
                stopConditions(policy, ctx),
                terminalStatus,
                terminalReason,
                HumanReadable.render(ctx, policy, originalProposal, finalAction, schedule, terminalReason),
                null);
    }

    /**
     * Derived, not random. The same context must produce a byte-identical record
     * on every run — a random id would make every record differ and quietly
     * defeat the reproducibility the backtest depends on.
     */
    private static UUID deterministicId(DecisionContext ctx, int position, InterventionKind action) {
        String canonical = ctx.caseId() + "|" + position + "|" + ctx.now() + "|" + action;
        return UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Decisive(Guardrail guardrail, Guardrail.Outcome outcome) {
    }

    /**
     * Evaluates every guardrail, in table order, and returns the first decisive
     * one. Evaluation is side-effect-free, so running all eleven for the record
     * and then honouring only the first non-ALLOW is safe.
     */
    private Optional<Decisive> firstDecisive(DecisionContext ctx, InterventionKind proposed,
                                             PolicyTable.Policy policy) {
        for (Guardrail guardrail : guardrails) {
            if (ctx.disabledGuardrails().contains(guardrail.id())) {
                continue;
            }
            Guardrail.Outcome outcome = guardrail.evaluate(ctx, proposed, policy);
            if (outcome.isDecisive()) {
                return Optional.of(new Decisive(guardrail, outcome));
            }
        }
        return Optional.empty();
    }

    private List<DecisionRecord.GuardrailEvaluation> evaluateAll(
            DecisionContext ctx, InterventionKind proposed, PolicyTable.Policy policy) {
        List<DecisionRecord.GuardrailEvaluation> out = new ArrayList<>(guardrails.size());
        for (Guardrail guardrail : guardrails) {
            if (ctx.disabledGuardrails().contains(guardrail.id())) {
                continue;
            }
            Guardrail.Outcome outcome = guardrail.evaluate(ctx, proposed, policy);
            String result = outcome.result() == Guardrail.Outcome.Result.NOT_APPLICABLE
                    ? "N/A" : outcome.result().name();
            out.add(new DecisionRecord.GuardrailEvaluation(
                    guardrail.id(), guardrail.name(), proposed.name(), result, outcome.detail()));
        }
        return out;
    }

    /**
     * UNDIAGNOSED first, then low confidence, then the cause table. Order matters:
     * an abstention is always UNDIAGNOSED and must take the clearance path rather
     * than the generic low-confidence ladder.
     */
    private Selection selectPolicy(DecisionContext ctx) {
        if (ctx.cause() == FailureCause.UNDIAGNOSED) {
            List<DecisionRecord.ClearanceCheck> checks = clearanceChecks(ctx);
            boolean cleared = checks.stream().allMatch(DecisionRecord.ClearanceCheck::cleared);
            return new Selection(
                    cleared ? policies.undiagnosedCleared() : policies.undiagnosedUncleared(), checks);
        }
        if (ctx.diagnosisConfidence() < CONFIDENCE_FLOOR
                || ctx.diagnosisMethod() == Diagnosis.DiagnosisMethod.ABSTAIN) {
            return new Selection(policies.lowConfidence(), List.of());
        }
        return new Selection(policies.forCause(ctx.cause()), List.of());
    }

    /**
     * Each check must clear affirmatively. Unknown is not cleared — a null
     * mandate status or an unreadable risk flag means we could not establish that
     * a debit is safe, which is not the same as establishing that it is.
     */
    private static List<DecisionRecord.ClearanceCheck> clearanceChecks(DecisionContext ctx) {
        List<DecisionRecord.ClearanceCheck> checks = new ArrayList<>(4);

        checks.add(ctx.mandateStatus() == null
                ? new DecisionRecord.ClearanceCheck("mandate_active", false, "mandate status unavailable")
                : new DecisionRecord.ClearanceCheck("mandate_active", ctx.mandateIsActive(),
                        "mandate status is " + ctx.mandateStatus()));

        boolean validityKnown = ctx.mandateValidFrom() != null && ctx.mandateValidUntil() != null;
        checks.add(!validityKnown
                ? new DecisionRecord.ClearanceCheck("within_validity", false, "mandate validity window unavailable")
                : new DecisionRecord.ClearanceCheck("within_validity", ctx.withinMandateValidity(),
                        "now vs " + ctx.mandateValidFrom() + ".." + ctx.mandateValidUntil()));

        checks.add(ctx.mandateMaxAmountPaise() == null
                ? new DecisionRecord.ClearanceCheck("within_cap", false, "mandate cap unavailable")
                : new DecisionRecord.ClearanceCheck("within_cap", ctx.withinMandateCap(),
                        ctx.amountPaise() + " vs cap " + ctx.mandateMaxAmountPaise()));

        checks.add(ctx.riskFlagged() == null
                ? new DecisionRecord.ClearanceCheck("no_risk_flag", false, "risk flag unavailable")
                : new DecisionRecord.ClearanceCheck("no_risk_flag", !ctx.riskFlagged(),
                        ctx.riskFlagged() ? "customer is risk-flagged" : "customer is not risk-flagged"));

        return List.copyOf(checks);
    }

    private static ScheduleExpression.Resolved resolveSchedule(
            DecisionContext ctx, PolicyTable.Policy policy, InterventionKind action,
            java.time.Instant deferUntil, int position) {
        if (deferUntil != null) {
            return new ScheduleExpression.Resolved(deferUntil, "deferred by a guardrail");
        }
        return ScheduleExpression.resolve(policy.scheduleFor(action), ctx.now(), ctx.caseId(),
                position + 1, ctx.inferredSalaryDay());
    }

    private static String terminalReasonFor(InterventionKind action, PolicyTable.Policy policy,
                                            DecisionContext ctx) {
        if (policy.terminalReason() != null) {
            return policy.terminalReason();
        }
        return action == InterventionKind.HUMAN_ESCALATION
                ? "Routed to a human: no safe automated action remains for " + ctx.cause()
                : "Abandoned: no remaining action can recover this case";
    }

    private static List<String> stopConditions(PolicyTable.Policy policy, DecisionContext ctx) {
        List<String> conditions = new ArrayList<>(3);
        conditions.add("max_debit_attempts=" + policy.maxDebitAttempts());
        if (policy.maxNudges() != null) {
            conditions.add("max_nudges=" + policy.maxNudges());
        }
        conditions.add("billing_cycle_end=" + ctx.billingCycleEnd());
        return List.copyOf(conditions);
    }
}

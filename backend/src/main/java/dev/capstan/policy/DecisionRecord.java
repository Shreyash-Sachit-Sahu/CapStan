package dev.capstan.policy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The audit artifact. Persisted verbatim into {@code intervention.decision_json}
 * and rendered in the cockpit's why-panel — the object a judge reads when they
 * ask why the system did what it did.
 *
 * <p>{@code guardrails} carries an entry for every guardrail on every decision,
 * including the ones that did not apply. A trail that records only what happened
 * cannot demonstrate boundedness; the nudge that was *not* sent at 22:40 IST is
 * the evidence, and it only exists if the non-firing evaluations are written down.
 */
public record DecisionRecord(
        UUID decisionId,
        UUID caseId,
        Instant at,
        int policyVersion,
        String policyName,
        Diagnosis diagnosis,
        int ladderPosition,
        InterventionKind proposedAction,
        List<GuardrailEvaluation> guardrails,
        List<ClearanceCheck> clearanceChecks,
        InterventionKind finalAction,
        Instant scheduledFor,
        String scheduleRationale,
        long amountAtRiskPaise,
        List<String> stopConditions,
        String terminalStatus,
        String terminalReason,
        String humanReadable,
        Message customerMessage) {

    /**
     * Copy for a comms action, after the validator. Null for debit actions.
     * Attached by the service layer rather than the engine, because generating
     * it may involve the model and the engine must stay pure.
     */
    public record Message(String text, boolean fromTemplate, String rejectionReason) {
    }

    public DecisionRecord withCustomerMessage(Message message) {
        return new DecisionRecord(decisionId, caseId, at, policyVersion, policyName, diagnosis,
                ladderPosition, proposedAction, guardrails, clearanceChecks, finalAction,
                scheduledFor, scheduleRationale, amountAtRiskPaise, stopConditions,
                terminalStatus, terminalReason, humanReadable, message);
    }

    /** Flattened copy of the Phase 03 result, so the record stands alone. */
    public record Diagnosis(String cause, double confidence, String method, String evidence) {
    }

    /**
     * One guardrail, evaluated against one proposed action. The ladder may be
     * walked more than once per decision, so entries carry the action they were
     * applied to -- otherwise "G5 blocked" would not say what it blocked.
     */
    public record GuardrailEvaluation(String id, String name, String appliedTo,
                                      String result, String detail) {
    }

    /**
     * Only populated on the UNDIAGNOSED path. Each check must clear
     * affirmatively; {@code cleared=false} covers both "the answer was no" and
     * "we could not determine it", and the {@code detail} says which.
     */
    public record ClearanceCheck(String name, boolean cleared, String detail) {
    }

    public boolean isTerminal() {
        return terminalStatus != null;
    }
}

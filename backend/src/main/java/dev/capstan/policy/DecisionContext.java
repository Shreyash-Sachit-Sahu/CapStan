package dev.capstan.policy;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything the policy engine is allowed to know, assembled by the service layer.
 *
 * <p>The engine takes this and returns a {@link DecisionRecord}. It holds no
 * database handle, no clock, and no classifier — the same decision must come out
 * of the same context on every run, and the property test drives ten thousand of
 * these without a Spring context or a fixture.
 *
 * <p><b>Boxed types are load-bearing.</b> {@code riskFlagged} and the mandate
 * fields are nullable so that "we could not determine this" is representable and
 * distinct from "the answer is no". The UNDIAGNOSED clearance branch treats null
 * as a failure to clear.
 */
public record DecisionContext(
        UUID caseId,
        Instant now,

        // diagnosis
        FailureCause cause,
        double diagnosisConfidence,
        Diagnosis.DiagnosisMethod diagnosisMethod,
        String diagnosisEvidence,

        // case
        long amountPaise,
        Instant firstFailedAt,
        Instant billingCycleEnd,

        // mandate — nullable where absence must not read as permission
        String mandateStatus,
        Instant mandateValidFrom,
        Instant mandateValidUntil,
        Long mandateMaxAmountPaise,
        String rail,
        String alternateRail,

        // customer
        boolean contactOptedOut,
        Boolean riskFlagged,
        String preferredLocale,

        // history within this billing cycle
        int ladderPosition,
        int debitAttemptsMade,
        int commsSentInCycle,
        Instant lastDebitAttemptAt,
        Instant lastCommsAt,
        Integer inferredSalaryDay,

        // batch-level signal for G11
        int recentAttemptsSameReason,
        int recentFailuresSameReason,
        String failureReason,

        // re-authorisation, observed rather than assumed
        Instant reauthRequestedAt,
        Instant reauthCompletedAt,

        /**
         * Guardrail ids the engine must skip. Always empty in production --
         * { DecisionService} passes { Set.of()} and nothing else can
         * reach this constructor. It exists so Phase 07 can price a guardrail:
         * every other ablation reshapes one context field, and a guardrail that
         * reads the clock rather than the context (G7 quiet hours) could not be
         * measured any other way.
         */
        java.util.Set<String> disabledGuardrails) {

    public boolean mandateIsActive() {
        return "ACTIVE".equalsIgnoreCase(mandateStatus);
    }

    public boolean withinMandateValidity() {
        return mandateValidFrom != null
                && mandateValidUntil != null
                && !now.isBefore(mandateValidFrom)
                && !now.isAfter(mandateValidUntil);
    }

    public boolean withinMandateCap() {
        return mandateMaxAmountPaise != null && amountPaise <= mandateMaxAmountPaise;
    }

    public boolean pastCycleEnd() {
        return billingCycleEnd != null && now.isAfter(billingCycleEnd);
    }
}

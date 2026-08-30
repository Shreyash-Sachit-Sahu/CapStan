package dev.capstan.backtest;

import dev.capstan.policy.DecisionContext;

/**
 * One mechanism switched off, everything else identical.
 *
 * <p>"Our agent is better" is worth much less than "this decision earned these
 * rupees", and an ablation is the only way to say the second thing honestly.
 * Each entry disables exactly one mechanism by reshaping a single field of the
 * {@link DecisionContext} the policy engine reads — the engine, the guardrails,
 * the ladders and the execution path are byte-identical across every run, so the
 * difference in recovered rupees is attributable to the mechanism and nothing
 * else.
 *
 * <p>Reshaping the input rather than branching inside the engine matters: a flag
 * threaded through {@code PolicyEngine} would make the ablation runs execute
 * different code from the real one, and the attribution would measure the flag
 * as much as the mechanism.
 */
public enum Ablation {

    /** Everything on. The reference run. */
    NONE("none"),

    /**
     * Payday-window timing. Handled outside the context: the decision still
     * chooses PAYDAY_RETRY, but the runner rewrites its schedule to a flat +48h,
     * which is what a system without salary-cycle inference would do.
     */
    PAYDAY_TIMING("payday-window timing"),

    /**
     * Reconcile-before-retry. The worker skips the predecessor gate and the
     * reconciler never runs, so an unknown outcome is treated as a failure —
     * which is how double charges happen.
     */
    RECONCILE_BEFORE_RETRY("reconcile-before-retry"),

    /**
     * Re-auth routing. Raising the mandate cap out of reach stops G4 ever
     * substituting a re-auth link for an over-cap debit, so those cases spend
     * retries instead.
     */
    REAUTH_ROUTING("re-auth routing"),

    /** Rail switch. With no alternate on file, G9 blocks it and the ladder advances. */
    RAIL_SWITCH("rail switch"),

    /**
     * Quiet hours. G7 stops Capstan messaging between 21:00 and 09:00 IST; the
     * baseline has no such restriction and can nudge at 03:00. This prices that
     * asymmetry instead of leaving it as an unmeasured concession.
     */
    QUIET_HOURS("G7 quiet hours"),

    /**
     * Low-confidence conservatism. Forcing full confidence stops the engine ever
     * taking the cautious branch for a shaky diagnosis.
     */
    LOW_CONFIDENCE_CONSERVATISM("low-confidence conservatism");

    private final String label;

    Ablation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean skipReconcile() {
        return this == RECONCILE_BEFORE_RETRY;
    }

    public boolean flattenPaydaySchedule() {
        return this == PAYDAY_TIMING;
    }

    /** Rebuilds the context with one field changed. Records have no {@code with}. */
    public DecisionContext shape(DecisionContext c) {
        Long cap = this == REAUTH_ROUTING ? Long.MAX_VALUE : c.mandateMaxAmountPaise();
        String alternate = this == RAIL_SWITCH ? null : c.alternateRail();
        double confidence = this == LOW_CONFIDENCE_CONSERVATISM ? 1.0 : c.diagnosisConfidence();
        java.util.Set<String> off = this == QUIET_HOURS ? java.util.Set.of("G7") : c.disabledGuardrails();

        if (cap == c.mandateMaxAmountPaise() && alternate == c.alternateRail()
                && confidence == c.diagnosisConfidence() && off.equals(c.disabledGuardrails())) {
            return c;
        }
        return new DecisionContext(
                c.caseId(), c.now(),
                c.cause(), confidence, c.diagnosisMethod(), c.diagnosisEvidence(),
                c.amountPaise(), c.firstFailedAt(), c.billingCycleEnd(),
                c.mandateStatus(), c.mandateValidFrom(), c.mandateValidUntil(),
                cap, c.rail(), alternate,
                c.contactOptedOut(), c.riskFlagged(), c.preferredLocale(),
                c.ladderPosition(), c.debitAttemptsMade(), c.commsSentInCycle(),
                c.lastDebitAttemptAt(), c.lastCommsAt(), c.inferredSalaryDay(),
                c.recentAttemptsSameReason(), c.recentFailuresSameReason(), c.failureReason(), off);
    }
}

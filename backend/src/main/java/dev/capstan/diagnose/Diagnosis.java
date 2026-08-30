package dev.capstan.diagnose;

/**
 * The outcome of the diagnosis cascade for one case.
 *
 * @param evidence short, human-readable justification — the rule that matched or
 *                 the signal the classifier quoted. Surfaced in the Phase 08
 *                 why-panel, so it must make sense to someone who has not read
 *                 the code.
 */
public record Diagnosis(
        FailureCause cause,
        double confidence,
        DiagnosisMethod method,
        String evidence) {

    public enum DiagnosisMethod {
        /** Tier 1: exact/wildcard match on Razorpay source:step:reason. */
        CODE_MAP,
        /** Tier 2: keyword or regex over the bank narration. */
        NARRATION,
        /** Tier 3: closed-set LLM classifier. */
        LLM,
        /** No tier produced a usable label. Phase 04 routes these conservatively. */
        ABSTAIN
    }

    /**
     * Abstention is correct behaviour, not failure. It lands on UNDIAGNOSED at
     * zero confidence — deliberately not TECHNICAL_DECLINE_UNKNOWN, which is
     * {@code retryable} and would make the system fail open on unknown input.
     * Phase 04's low-confidence branch takes it from here and must clear the case
     * affirmatively before any debit.
     */
    public static Diagnosis abstain(String evidence) {
        return new Diagnosis(FailureCause.UNDIAGNOSED, 0.0,
                DiagnosisMethod.ABSTAIN, evidence);
    }

    public Diagnosis withCause(FailureCause replacement, String why) {
        return new Diagnosis(replacement, confidence, method, why);
    }
}

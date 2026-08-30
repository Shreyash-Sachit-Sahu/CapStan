package dev.capstan.diagnose;

/**
 * Tier 3. Deliberately an interface with an abstaining default, for three reasons:
 * a missing API key must never block the pipeline, the deterministic tiers can be
 * measured on their own, and Phase 09's "LLM is down" drill becomes a
 * configuration change rather than a code path nobody has exercised.
 */
public interface LlmClassifier {

    Diagnosis classify(DiagnosisInput input);

    /** False when no credentials are configured; the eval report says so explicitly. */
    boolean enabled();

    /**
     * Optional startup call. Structured outputs compile a grammar for a new schema
     * on first use and cache it for 24 hours, so the first real request would
     * otherwise carry compilation latency and skew reported mean latency.
     */
    default void warmUp() {
    }

    /** Used when no API key is configured. Tier 1 and Tier 2 still run. */
    final class Abstaining implements LlmClassifier {

        private final String why;

        public Abstaining(String why) {
            this.why = why;
        }

        @Override
        public Diagnosis classify(DiagnosisInput input) {
            return Diagnosis.abstain(why);
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }
}

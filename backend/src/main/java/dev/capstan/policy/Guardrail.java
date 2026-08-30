package dev.capstan.policy;

import java.time.Instant;

/**
 * A single bound on what the policy may do.
 *
 * <p>Evaluation must be side-effect-free. All eleven run on every decision so
 * that the record is complete, and only then does the first non-ALLOW in table
 * order decide the outcome. If evaluating a guardrail changed anything, running
 * the ones after the decisive rule would change behaviour.
 */
public interface Guardrail {

    String id();

    String name();

    Outcome evaluate(DecisionContext ctx, InterventionKind proposed, PolicyTable.Policy policy);

    /**
     * The brief's ALLOW / DEFER / BLOCK is too coarse for what the table actually
     * specifies: G5 blocks and advances the ladder, G4 blocks and substitutes a
     * re-auth link, G2 blocks and terminates the case. Collapsing those into one
     * BLOCK would silently drop the substitution paths.
     */
    record Outcome(
            Result result,
            String detail,
            Instant deferUntil,
            Disposition disposition,
            InterventionKind substitute,
            String terminalStatus) {

        public enum Result {
            /** This guardrail permits the proposed action. */
            ALLOW,
            /** This guardrail does not govern this kind of action. Recorded, not decisive. */
            NOT_APPLICABLE,
            /** Permitted, but not yet. */
            DEFER,
            /** Not permitted. See {@link Disposition} for what happens instead. */
            BLOCK
        }

        public enum Disposition {
            /** Try the next rung of the ladder. */
            ADVANCE_LADDER,
            /** Replace the proposed action with a specific safer one. */
            SUBSTITUTE,
            /** End the case in the given status. */
            TERMINATE
        }

        public static Outcome allow(String detail) {
            return new Outcome(Result.ALLOW, detail, null, null, null, null);
        }

        public static Outcome notApplicable(String detail) {
            return new Outcome(Result.NOT_APPLICABLE, detail, null, null, null, null);
        }

        public static Outcome defer(Instant until, String detail) {
            return new Outcome(Result.DEFER, detail, until, null, null, null);
        }

        public static Outcome advanceLadder(String detail) {
            return new Outcome(Result.BLOCK, detail, null, Disposition.ADVANCE_LADDER, null, null);
        }

        public static Outcome substitute(InterventionKind action, String detail) {
            return new Outcome(Result.BLOCK, detail, null, Disposition.SUBSTITUTE, action, null);
        }

        public static Outcome terminate(String caseStatus, String detail) {
            return new Outcome(Result.BLOCK, detail, null, Disposition.TERMINATE, null, caseStatus);
        }

        /** ALLOW and NOT_APPLICABLE are recorded but never change the outcome. */
        public boolean isDecisive() {
            return result == Result.DEFER || result == Result.BLOCK;
        }
    }
}

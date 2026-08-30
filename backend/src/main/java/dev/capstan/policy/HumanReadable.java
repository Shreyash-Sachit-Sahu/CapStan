package dev.capstan.policy;

/**
 * Renders the one-sentence explanation carried on every DecisionRecord.
 *
 * <p>Deterministic templating, written *from* the structured record. The model
 * may later rewrite this line for readability, but the record has to be complete
 * and truthful without it — an explanation that only exists when an API is up is
 * not an audit trail.
 */
final class HumanReadable {

    private HumanReadable() {
    }

    static String render(DecisionContext ctx, PolicyTable.Policy policy,
                         InterventionKind proposed, InterventionKind chosen,
                         ScheduleExpression.Resolved schedule, String terminalReason) {

        String cause = ctx.cause().humanSummary.toLowerCase();
        String amount = NudgeCopy.formatAmount(ctx.amountPaise());

        if (terminalReason != null) {
            return switch (chosen) {
                case HUMAN_ESCALATION -> "Stopped and queued for a human. " + terminalReason + ".";
                case ABANDON -> "Closed without recovering " + amount + ". " + terminalReason + ".";
                default -> terminalReason + ".";
            };
        }

        String substituted = proposed != chosen
                ? " A " + proposed + " was proposed first and a guardrail replaced it." : "";

        return switch (chosen) {
            case PAYDAY_RETRY -> amount + " failed because " + cause
                    + ". Waiting for the salary window rather than retrying now, because a retry "
                    + "before the balance arrives would spend one of " + policy.maxDebitAttempts()
                    + " permitted attempts for nothing. " + schedule.rationale() + "." + substituted;
            case SCHEDULED_RETRY -> "Holding the retry of " + amount + " (" + cause + "). "
                    + schedule.rationale() + "." + substituted;
            case IMMEDIATE_RETRY -> "Retrying " + amount + " now: " + cause
                    + " is usually transient." + substituted;
            case RECONCILE_ONLY -> "Not retrying yet. The previous attempt may already have "
                    + "succeeded at the bank, so we are asking the gateway what happened before "
                    + "risking a second debit." + substituted;
            case RAIL_SWITCH -> "Switching to " + ctx.alternateRail() + " for " + amount
                    + ", because " + cause + " is specific to the original instrument." + substituted;
            case CUSTOMER_NUDGE -> "Contacting the customer about " + amount + " rather than "
                    + "retrying, because " + cause + " needs them to act." + substituted;
            case REAUTH_LINK -> "Sending a re-authorisation link for " + amount
                    + ". No debit on the existing mandate can succeed while " + cause + "."
                    + substituted;
            default -> "Chose " + chosen + " for " + amount + " (" + cause + ")." + substituted;
        };
    }
}

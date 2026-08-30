package dev.capstan.policy;

/** The complete set of actions the policy engine may authorise. Nothing else exists. */
public enum InterventionKind {

    RECONCILE_ONLY(false, false),   // no debit; ask the gateway what actually happened
    IMMEDIATE_RETRY(true, false),   // same rail, now -- timeouts and transient declines
    SCHEDULED_RETRY(true, false),   // same rail, at a computed time
    PAYDAY_RETRY(true, false),      // same rail, at the next salary-cycle window
    RAIL_SWITCH(true, false),       // alternate instrument on file
    CUSTOMER_NUDGE(false, true),    // comms only, no debit
    REAUTH_LINK(false, true),       // send mandate re-authorisation link
    HUMAN_ESCALATION(false, false), // queue for a human, stop automation
    ABANDON(false, false);          // terminal, with reason

    /** Does this action move money? Guardrails G3-G6 and G11 apply only to these. */
    public final boolean isDebit;
    /** Does this action contact the customer? G1, G7 and G8 apply only to these. */
    public final boolean isComms;

    InterventionKind(boolean isDebit, boolean isComms) {
        this.isDebit = isDebit;
        this.isComms = isComms;
    }

    public boolean isTerminal() {
        return this == ABANDON || this == HUMAN_ESCALATION;
    }
}

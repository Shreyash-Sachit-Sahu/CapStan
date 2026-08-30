package dev.capstan.diagnose;

import java.time.Instant;

/**
 * Resolves revoked-versus-expired after classification, from data we already hold.
 *
 * <p>Razorpay reports both as {@code mandate_not_active} and does not
 * distinguish them. That distinction is genuinely undecidable from the payload —
 * none of the six fields the classifier sees mentions mandate validity — so
 * asking the LLM to choose would be asking it to guess, and the confusion matrix
 * would score the guess as model error when it is really missing input.
 *
 * <p>We know the answer deterministically: a mandate whose validity ended before
 * the debit was attempted was expired; anything else was revoked. Doing it here
 * leaves Tier 3 with the ambiguity that is actually irreducible.
 */
public final class MandateStateRefiner {

    private MandateStateRefiner() {
    }

    public static Diagnosis refine(Diagnosis diagnosis, Instant mandateValidUntil, Instant firstFailedAt) {
        if (diagnosis.cause() != FailureCause.MANDATE_REVOKED
                && diagnosis.cause() != FailureCause.MANDATE_EXPIRED) {
            return diagnosis;
        }
        if (mandateValidUntil == null || firstFailedAt == null) {
            return diagnosis;
        }
        boolean expired = mandateValidUntil.isBefore(firstFailedAt);
        FailureCause resolved = expired ? FailureCause.MANDATE_EXPIRED : FailureCause.MANDATE_REVOKED;
        if (resolved == diagnosis.cause()) {
            return diagnosis;
        }
        String why = diagnosis.evidence() + "; refined to " + resolved
                + " because mandate validity ended " + (expired ? "before" : "after")
                + " the first failed debit";
        return diagnosis.withCause(resolved, why);
    }
}

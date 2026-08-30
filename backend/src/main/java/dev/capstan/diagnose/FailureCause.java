package dev.capstan.diagnose;

import java.util.Optional;

/**
 * Closed taxonomy. The classifier may not invent members, and Phase 04's policy
 * table keys off these constants, so the metadata lives here rather than in the
 * policy layer where it could drift.
 */
public enum FailureCause {

    INSUFFICIENT_FUNDS(true, false, "Balance shortfall at debit time"),
    ISSUER_DECLINE_TEMPORARY(true, false, "Issuer-side transient decline"),
    NETWORK_TIMEOUT(true, false, "No terminal response from gateway"),
    DO_NOT_HONOUR(true, false, "Ambiguous issuer decline"),
    CARD_EXPIRED(false, true, "Instrument past expiry"),
    MANDATE_LIMIT_EXCEEDED(false, true, "Debit above mandate per-txn cap"),
    MANDATE_REVOKED(false, false, "Customer withdrew authorisation"),
    MANDATE_EXPIRED(false, true, "Mandate validity elapsed"),
    AUTHENTICATION_FAILED(false, true, "AFA/3DS not completed"),
    ACCOUNT_CLOSED(false, false, "Underlying account no longer exists"),
    RISK_BLOCKED(false, false, "Blocked by risk controls"),
    TECHNICAL_DECLINE_UNKNOWN(true, false, "Unclassified technical decline"),

    /**
     * No tier could determine a cause. This is the absence of a finding, not a
     * finding, and it is the landing label for abstention.
     *
     * <p>It exists because TECHNICAL_DECLINE_UNKNOWN was doing two incompatible
     * jobs. When the code map resolves {@code gateway_technical_error} that is an
     * affirmative diagnosis and {@code retryable = true} is correct. When the
     * cascade abstains, inheriting that flag means the system fails *open* on
     * unknown input — an abstention on a risk-blocked debit would present as
     * retryable, and no downstream branch could tell otherwise, because if we
     * knew it was risk-blocked we would not have abstained.
     *
     * <p>Money actions fail closed. {@code retryable = false} here means no
     * automated debit follows from not knowing; Phase 04 may still permit one
     * retry, but only after affirmatively clearing the case against our own
     * records rather than by inheriting a default.
     */
    UNDIAGNOSED(false, false, "No tier could determine a cause");

    /** May a further debit attempt on the same rail ever succeed? */
    public final boolean retryable;
    /** Does recovery require customer re-authorisation? */
    public final boolean needsReauth;
    public final String humanSummary;

    FailureCause(boolean retryable, boolean needsReauth, String humanSummary) {
        this.retryable = retryable;
        this.needsReauth = needsReauth;
        this.humanSummary = humanSummary;
    }

    /**
     * Case-insensitive lookup. Structured outputs constrain the model to the
     * schema's enum values but do not guarantee their capitalisation, so a
     * returned "insufficient_funds" must resolve to INSUFFICIENT_FUNDS rather
     * than being discarded as a schema violation — otherwise the miss shows up
     * as sporadic abstentions that look like model quality.
     */
    public static Optional<FailureCause> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.strip().toUpperCase();
        for (FailureCause cause : values()) {
            if (cause.name().equals(normalised)) {
                return Optional.of(cause);
            }
        }
        return Optional.empty();
    }

    /**
     * UNDIAGNOSED is a system state, so no tier may assert it — a classifier
     * returning it would be claiming to have found that nothing was found.
     * Classifier paths use this; {@link #parse} stays general for database
     * round-tripping.
     */
    public static Optional<FailureCause> parseAssertable(String raw) {
        return parse(raw).filter(cause -> cause != UNDIAGNOSED);
    }
}

package dev.capstan.diagnose;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Everything the diagnosis cascade is allowed to see about a failure.
 *
 * <p>This is a deliberate allow-list, not a convenience DTO. The customer's
 * contact details, the amount, and above all the oracle are absent by
 * construction, so no tier — least of all the one that leaves the building —
 * can read them even by accident.
 */
public record DiagnosisInput(
        String rawErrorCode,
        String rawErrorReason,
        String rawErrorDesc,
        String rawErrorSource,
        String rawErrorStep,
        String bankNarration,
        String rail) {

    /** Tier 1 lookup key: source:step:reason. */
    public String lookupKey() {
        return nullToEmpty(rawErrorSource) + ":" + nullToEmpty(rawErrorStep) + ":"
                + nullToEmpty(rawErrorReason);
    }

    /**
     * Stable hash of the payload, used as the Tier 3 cache key. Identical
     * failures are common in a batch and each one is an avoidable API call.
     */
    public String signature() {
        String canonical = String.join("",
                nullToEmpty(rawErrorCode), nullToEmpty(rawErrorReason), nullToEmpty(rawErrorDesc),
                nullToEmpty(rawErrorSource), nullToEmpty(rawErrorStep),
                nullToEmpty(bankNarration), nullToEmpty(rail));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

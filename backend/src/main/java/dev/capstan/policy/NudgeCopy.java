package dev.capstan.policy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Customer message copy: deterministic templates, plus the validator every
 * message must pass before it can be sent.
 *
 * <p>The validator is the point. Generated copy is convenient; copy that cannot
 * invent an amount, cannot smuggle in a link, and cannot reach for urgency
 * language is the part worth having. Anything that fails falls back to the
 * template, and the rejection is logged rather than swallowed.
 */
public final class NudgeCopy {

    /** Words that turn a factual reminder into pressure. Non-negotiable. */
    static final List<String> BANNED = List.of("immediately", "final warning", "legal");
    static final int MAX_CHARS = 320;

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private NudgeCopy() {
    }

    public record Copy(String text, boolean fromTemplate, String rejectionReason) {
        public boolean wasRejected() {
            return rejectionReason != null;
        }
    }

    public record Validation(boolean valid, String reason) {
        static Validation ok() {
            return new Validation(true, null);
        }

        static Validation fail(String reason) {
            return new Validation(false, reason);
        }
    }

    /** Amount is formatted once, here, and injected — never generated. */
    public static String formatAmount(long amountPaise) {
        return String.format(Locale.ENGLISH, "Rs %,.2f", amountPaise / 100.0);
    }

    public static String template(InterventionKind kind, String locale, long amountPaise, String link) {
        String amount = formatAmount(amountPaise);
        boolean hinglish = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("hi");

        return switch (kind) {
            case CUSTOMER_NUDGE -> hinglish
                    ? "Aapka " + amount + " ka autopay payment complete nahi ho paya. "
                      + "Account me balance add karke rakhein, hum dobara try karenge."
                    : "Your autopay payment of " + amount + " could not be completed. "
                      + "Please keep your account funded and we will try again.";
            case REAUTH_LINK -> hinglish
                    ? "Aapka " + amount + " ka autopay mandate ab active nahi hai. "
                      + "Dobara set up karne ke liye: " + link
                    : "Your autopay mandate for " + amount + " is no longer active. "
                      + "You can set it up again here: " + link;
            default -> throw new IllegalArgumentException(kind + " does not send customer copy");
        };
    }

    /**
     * @param text          candidate message
     * @param requiredAmount the injected amount string that must appear verbatim
     * @param allowedLink   the only permitted URL, or null if the message may contain none
     */
    public static Validation validate(String text, String requiredAmount, String allowedLink) {
        if (text == null || text.isBlank()) {
            return Validation.fail("empty message");
        }
        if (text.length() > MAX_CHARS) {
            return Validation.fail("message is " + text.length() + " chars, limit " + MAX_CHARS);
        }
        if (!text.contains(requiredAmount)) {
            return Validation.fail("message does not contain the injected amount '" + requiredAmount + "'");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String banned : BANNED) {
            if (lower.contains(banned)) {
                return Validation.fail("message contains banned phrase '" + banned + "'");
            }
        }
        var matcher = URL.matcher(text);
        while (matcher.find()) {
            String found = matcher.group().replaceAll("[.,;:)]+$", "");
            if (allowedLink == null || !allowedLink.contains(found)) {
                return Validation.fail("message contains a link that was not injected: " + found);
            }
        }
        return Validation.ok();
    }

    /** Validates generated copy and falls back to the template if it fails. */
    public static Copy validated(String generated, InterventionKind kind, String locale,
                                 long amountPaise, String link) {
        String fallback = template(kind, locale, amountPaise, link);
        if (generated == null || generated.isBlank()) {
            return new Copy(fallback, true, null);
        }
        Validation validation = validate(generated, formatAmount(amountPaise), link);
        return validation.valid()
                ? new Copy(generated.strip(), false, null)
                : new Copy(fallback, true, validation.reason());
    }
}

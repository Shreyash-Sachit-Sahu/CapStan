package dev.capstan.diagnose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a Tier 3 response into a {@link Diagnosis}, or abstains.
 *
 * <p>Kept even though structured outputs constrain the response to a schema.
 * Two documented cases still produce output that does not match: a refusal, and
 * a max_tokens cutoff mid-object. Both arrive as ordinary responses, so the
 * parser has to treat "did not match the schema" as a normal outcome rather than
 * an impossible one.
 *
 * <p>Everything unrecognised becomes ABSTAIN and is counted. Nothing is coerced
 * to a nearby label — a silently corrected answer is indistinguishable from a
 * correct one in the eval report, which is exactly the property we do not want.
 */
public final class LlmResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String ABSTAIN_LABEL = "ABSTAIN";

    private LlmResponseParser() {
    }

    /**
     * Models emit markdown fences uninvited even when told not to. Structured
     * outputs make this rare rather than impossible, and it costs one regex.
     */
    public static String stripFences(String text) {
        if (text == null) {
            return "";
        }
        return text.strip()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .strip();
    }

    public static Diagnosis parse(String rawText) {
        String cleaned = stripFences(rawText);
        if (cleaned.isEmpty()) {
            return Diagnosis.abstain("empty classifier response");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            return Diagnosis.abstain("classifier response was not valid JSON");
        }
        if (!node.isObject() || !node.hasNonNull("cause")) {
            return Diagnosis.abstain("classifier response had no cause field");
        }

        String label = node.get("cause").asText();
        if (ABSTAIN_LABEL.equalsIgnoreCase(label.strip())) {
            return Diagnosis.abstain(evidenceOf(node, "classifier abstained"));
        }

        // Case-insensitive: structured outputs constrain the value set but do not
        // guarantee capitalisation, and a case mismatch is not a schema violation.
        return FailureCause.parseAssertable(label)
                .map(cause -> new Diagnosis(cause, confidenceOf(node),
                        Diagnosis.DiagnosisMethod.LLM, evidenceOf(node, "classified by model")))
                .orElseGet(() -> Diagnosis.abstain(
                        "label outside taxonomy: '" + label + "'"));
    }

    private static double confidenceOf(JsonNode node) {
        if (!node.hasNonNull("confidence") || !node.get("confidence").isNumber()) {
            return 0.5;
        }
        return Math.clamp(node.get("confidence").asDouble(), 0.0, 1.0);
    }

    private static String evidenceOf(JsonNode node, String fallback) {
        if (node.hasNonNull("evidence") && !node.get("evidence").asText().isBlank()) {
            return node.get("evidence").asText().strip();
        }
        return fallback;
    }
}

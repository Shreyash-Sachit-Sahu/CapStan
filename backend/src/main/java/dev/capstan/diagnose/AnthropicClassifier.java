package dev.capstan.diagnose;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.models.messages.MessageCreateParams;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier 3 — closed-set classifier for payloads the deterministic tiers cannot
 * resolve. In practice that is one population: the bank declined and did not
 * tell Razorpay why, so the only remaining signal is a free-text narration in
 * whatever register the bank happened to use.
 *
 * <p>The model is never asked to choose between MANDATE_REVOKED and
 * MANDATE_EXPIRED. Razorpay reports both as mandate_not_active and the payload
 * contains nothing that distinguishes them, so that call is made deterministically
 * from mandate validity afterwards. What is left here is genuinely irreducible.
 */
public class AnthropicClassifier implements LlmClassifier {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClassifier.class);

    private static final String SYSTEM_PROMPT = """
            You classify failed recurring-payment debits for an Indian payments merchant.

            Choose exactly one label from this taxonomy, or ABSTAIN:
            INSUFFICIENT_FUNDS, ISSUER_DECLINE_TEMPORARY, NETWORK_TIMEOUT, DO_NOT_HONOUR,
            CARD_EXPIRED, MANDATE_LIMIT_EXCEEDED, MANDATE_REVOKED, MANDATE_EXPIRED,
            AUTHENTICATION_FAILED, ACCOUNT_CLOSED, RISK_BLOCKED, TECHNICAL_DECLINE_UNKNOWN

            These payloads reached you because the machine-readable reason was generic
            (payment_failed or payment_declined), which means the bank declined without
            disclosing why. The bank narration is usually the only real signal, and it
            may be terse shorthand, Hinglish, or transliterated Hindi. Read it for intent
            rather than matching keywords: "balance nahi hai" and "khate me paise kam hai"
            both mean INSUFFICIENT_FUNDS.

            A generic decline with no distinguishing signal is DO_NOT_HONOUR. That is a
            real answer, not a fallback -- it is what an undisclosed issuer decline is.

            Return ABSTAIN only when the payload contains nothing to reason from at all.
            Abstaining is correct behaviour; guessing is not.

            evidence: quote the specific text you used, under 20 words.
            """;

    /**
     * Schema for the structured response. {@code cause} is a String rather than a
     * Java enum on purpose: structured outputs constrain the value set but not its
     * capitalisation, and Jackson enum binding is case-sensitive, so a lowercase
     * label would surface as a deserialisation failure that looks like model error.
     * {@link FailureCause#parse} normalises instead.
     */
    public record Verdict(String cause, Double confidence, String evidence) {
    }

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;

    public AnthropicClassifier(String apiKey, String model, long timeoutMillis) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(timeoutMillis))
                .maxRetries(2)
                .build();
        this.model = model;
        this.maxTokens = 512;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * Structured outputs compile a grammar for an unseen schema on first use and
     * cache it for 24 hours. Paying that once at startup keeps it out of the
     * reported mean latency.
     */
    @Override
    public void warmUp() {
        try {
            long startedAt = System.nanoTime();
            classify(new DiagnosisInput("BAD_REQUEST_ERROR", "payment_failed",
                    "warm-up", "bank", "payment_authorization", "INSUFF BAL", "UPI_AUTOPAY"));
            log.info("Tier 3 grammar warm-up completed in {} ms",
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Tier 3 warm-up failed; classification will still be attempted: {}", e.toString());
        }
    }

    @Override
    public Diagnosis classify(DiagnosisInput input) {
        StructuredMessageCreateParams<Verdict> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .temperature(0.0)
                .system(SYSTEM_PROMPT)
                .addUserMessage(renderPayload(input))
                .outputConfig(Verdict.class)
                .build();

        StructuredMessage<Verdict> response;
        try {
            response = client.messages().create(params);
        } catch (RuntimeException e) {
            log.warn("Tier 3 call failed: {}", e.toString());
            return Diagnosis.abstain("classifier call failed: " + e.getClass().getSimpleName());
        }

        // Structured outputs constrain the response shape, but a refusal and a
        // max_tokens cutoff both still return 200 with content that need not match
        // the schema. Check the stop reason before trusting the payload.
        String stopReason = response.stopReason().map(Object::toString).orElse("").toLowerCase();
        if (stopReason.contains("refusal")) {
            return Diagnosis.abstain("classifier refused the request");
        }
        if (stopReason.contains("max_tokens")) {
            return Diagnosis.abstain("classifier response truncated at max_tokens");
        }

        Verdict verdict;
        try {
            verdict = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            // Schema-shaped binding can still fail on a partial or unexpected body.
            return Diagnosis.abstain("classifier response did not bind to the schema");
        }
        if (verdict == null || verdict.cause() == null) {
            return Diagnosis.abstain("classifier returned no cause");
        }

        // Case-insensitive: structured outputs pin the value set, not its
        // capitalisation, and a lowercase label is not a schema violation.
        return FailureCause.parseAssertable(verdict.cause())
                .map(cause -> new Diagnosis(
                        cause,
                        verdict.confidence() == null ? 0.5 : Math.clamp(verdict.confidence(), 0.0, 1.0),
                        Diagnosis.DiagnosisMethod.LLM,
                        verdict.evidence() == null || verdict.evidence().isBlank()
                                ? "classified by model" : verdict.evidence().strip()))
                .orElseGet(() -> LlmResponseParser.ABSTAIN_LABEL.equalsIgnoreCase(verdict.cause().strip())
                        ? Diagnosis.abstain("classifier abstained: " + orNone(verdict.evidence()))
                        : Diagnosis.abstain("label outside taxonomy: '" + verdict.cause() + "'"));
    }

    /**
     * The allow-list, rendered. Nothing about the customer, the amount, or the
     * oracle appears here, and cannot: DiagnosisInput does not carry them.
     */
    private static String renderPayload(DiagnosisInput input) {
        return """
                error_code: %s
                error_reason: %s
                error_description: %s
                error_source: %s
                error_step: %s
                bank_narration: %s
                rail: %s

                Reply with a JSON object with exactly these keys: cause, confidence, evidence.
                cause must be one taxonomy label or ABSTAIN. confidence is 0.0 to 1.0.
                """.formatted(
                orNone(input.rawErrorCode()),
                orNone(input.rawErrorReason()),
                orNone(input.rawErrorDesc()),
                orNone(input.rawErrorSource()),
                orNone(input.rawErrorStep()),
                orNone(input.bankNarration()),
                orNone(input.rail()));
    }

    private static String orNone(String value) {
        return (value == null || value.isBlank()) ? "(none)" : value;
    }
}

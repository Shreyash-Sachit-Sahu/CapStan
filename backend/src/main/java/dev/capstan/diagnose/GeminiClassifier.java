package dev.capstan.diagnose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Tier 3 on the Gemini free tier.
 *
 * <p>Chosen under a cost constraint, but it is also a defensible fit: the one
 * capability this tier exists for is reading transliterated Hindi and Hinglish
 * bank narrations, and Indic transliteration is a genuine strength there.
 *
 * <p>Plain REST rather than an SDK. The call is a single POST with a response
 * schema, {@link LlmResponseParser} already does the defensive parsing, and
 * Spring's RestClient is on the classpath — so an SDK would add a dependency and
 * a version to track without removing any code.
 */
public class GeminiClassifier implements LlmClassifier {

    private static final Logger log = LoggerFactory.getLogger(GeminiClassifier.class);
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 2;

    private static final String SYSTEM_PROMPT = """
            You classify failed recurring-payment debits for an Indian payments merchant.

            Choose exactly one label from this taxonomy, or ABSTAIN:
            INSUFFICIENT_FUNDS, ISSUER_DECLINE_TEMPORARY, NETWORK_TIMEOUT, DO_NOT_HONOUR,
            CARD_EXPIRED, MANDATE_LIMIT_EXCEEDED, MANDATE_REVOKED, MANDATE_EXPIRED,
            AUTHENTICATION_FAILED, ACCOUNT_CLOSED, RISK_BLOCKED, TECHNICAL_DECLINE_UNKNOWN

            These payloads reached you because the machine-readable reason was generic
            (payment_failed or payment_declined), which means the bank declined without
            disclosing why. The bank narration is usually the only real signal, and it may
            be terse shorthand, Hinglish, or transliterated Hindi. Read it for intent
            rather than matching keywords: "balance nahi hai" and "khate me paise kam hai"
            both mean INSUFFICIENT_FUNDS; "bank ne mana kar diya" is an undisclosed
            decline, not a specific cause.

            A generic decline with no distinguishing signal is DO_NOT_HONOUR. That is a
            real answer, not a fallback -- it is what an undisclosed issuer decline is.

            Return ABSTAIN only when the payload contains nothing to reason from at all.
            Abstaining is correct behaviour; guessing is not.

            evidence: quote the specific text you used, under 20 words.
            """;

    private final RestClient http;
    private final String model;
    private final String apiKey;
    private final long minIntervalMillis;

    private final ReentrantLock throttle = new ReentrantLock();
    private long lastCallAt = 0L;

    public GeminiClassifier(String apiKey, String model, long timeoutMillis, long minIntervalMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
        this.minIntervalMillis = minIntervalMillis;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void warmUp() {
        try {
            long startedAt = System.nanoTime();
            classify(new DiagnosisInput("BAD_REQUEST_ERROR", "payment_failed", "warm-up",
                    "bank", "payment_authorization", "INSUFF BAL", "UPI_AUTOPAY"));
            log.info("Tier 3 warm-up completed in {} ms", (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Tier 3 warm-up failed; classification will still be attempted: {}", e.toString());
        }
    }

    @Override
    public Diagnosis classify(DiagnosisInput input) {
        String body;
        try {
            body = MAPPER.writeValueAsString(requestBody(input));
        } catch (Exception e) {
            return Diagnosis.abstain("could not build classifier request");
        }

        String raw = postWithRetries(body);
        if (raw == null) {
            return Diagnosis.abstain("classifier unavailable after retries");
        }

        JsonNode response;
        try {
            response = MAPPER.readTree(raw);
        } catch (Exception e) {
            log.warn("Tier 3 returned unparseable body: {}", abbreviate(raw));
            return Diagnosis.abstain("classifier response was not valid JSON");
        }

        JsonNode candidate = response.path("candidates").path(0);
        // Gemini signals truncation and safety stops here. Both return 200 with a
        // body that need not satisfy the schema, so check before trusting it.
        String finishReason = candidate.path("finishReason").asText("");
        if ("MAX_TOKENS".equals(finishReason)) {
            return Diagnosis.abstain("classifier response truncated at max tokens");
        }
        if ("SAFETY".equals(finishReason) || "PROHIBITED_CONTENT".equals(finishReason)) {
            return Diagnosis.abstain("classifier declined the request");
        }

        String text = candidate.path("content").path("parts").path(0).path("text").asText("");
        return LlmResponseParser.parse(text);
    }

    /**
     * Returns the raw response body, or null if the call could not be completed.
     *
     * <p>Read as bytes rather than String on purpose: an error response does not
     * always carry a text content type, and letting message conversion fail turns
     * "the service was busy" into an opaque decoding exception.
     *
     * <p>429 and 503 are both expected on a free tier — 503 in particular is the
     * shared-capacity model being busy, not a fault in the request — so they are
     * retried with backoff before the case is allowed to abstain.
     */
    private String postWithRetries(String body) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            awaitSlot();
            try {
                Response response = http.post()
                        .uri(String.format(ENDPOINT, model))
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((request, res) -> new Response(
                                res.getStatusCode().value(),
                                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));

                if (response.status() / 100 == 2) {
                    return response.body();
                }

                // A per-day quota does not recover inside a request, so retrying it
                // only burns wall-clock and muddies the logs. Per-minute limits and
                // 5xx do recover, and are worth another attempt.
                boolean dailyQuotaExhausted = response.status() == 429
                        && response.body() != null
                        && response.body().contains("PerDay");
                boolean retryable = !dailyQuotaExhausted
                        && (response.status() == 429 || response.status() >= 500);

                log.warn("Tier 3 HTTP {}{} (attempt {}/{}): {}",
                        response.status(), dailyQuotaExhausted ? " daily-quota-exhausted" : "",
                        attempt + 1, MAX_RETRIES + 1, abbreviate(response.body()));
                if (!retryable) {
                    return null;
                }
            } catch (Exception e) {
                log.warn("Tier 3 call failed (attempt {}/{}): {}",
                        attempt + 1, MAX_RETRIES + 1, e.toString());
            }
            backOff(attempt);
        }
        return null;
    }

    private void backOff(int attempt) {
        try {
            // Exponential with jitter, so a busy window does not resynchronise
            // every waiting call onto the same retry instant.
            long base = 1_000L << attempt;
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(250, 750));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "(empty)";
        }
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    private record Response(int status, String body) {
    }

    /**
     * The free tier allows roughly 10-15 requests a minute. A cold eval run is 35
     * calls, so without spacing it would 429 partway through and present as a
     * model failure. Repeats are already absorbed by the Redis signature cache;
     * only this cold path needs limiting.
     */
    private void awaitSlot() {
        throttle.lock();
        try {
            long since = System.currentTimeMillis() - lastCallAt;
            long wait = minIntervalMillis - since;
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastCallAt = System.currentTimeMillis();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            throttle.unlock();
        }
    }

    private ObjectNode requestBody(DiagnosisInput input) {
        ObjectNode root = MAPPER.createObjectNode();

        root.putObject("systemInstruction").putArray("parts")
                .addObject().put("text", SYSTEM_PROMPT);

        root.putArray("contents").addObject().putArray("parts")
                .addObject().put("text", renderPayload(input));

        ObjectNode generation = root.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.put("temperature", 0);

        ObjectNode schema = generation.putObject("responseSchema");
        schema.put("type", "OBJECT");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode cause = properties.putObject("cause");
        cause.put("type", "STRING");
        ArrayNode allowed = cause.putArray("enum");
        Arrays.stream(FailureCause.values())
                .filter(c -> c != FailureCause.UNDIAGNOSED)   // a system state, not a finding
                .forEach(c -> allowed.add(c.name()));
        allowed.add(LlmResponseParser.ABSTAIN_LABEL);

        properties.putObject("confidence").put("type", "NUMBER");
        properties.putObject("evidence").put("type", "STRING");

        schema.putArray("required").add("cause").add("confidence").add("evidence");
        schema.putArray("propertyOrdering").add("cause").add("confidence").add("evidence");
        return root;
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

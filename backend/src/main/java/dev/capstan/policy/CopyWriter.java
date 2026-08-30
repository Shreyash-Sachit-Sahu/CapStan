package dev.capstan.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Drafts customer message copy. The model's entire remaining role.
 *
 * <p>It never chooses an action — by the time a writer is called, the policy has
 * already authorised a specific comms intervention. Generation, not decision.
 * Everything it returns passes {@link NudgeCopy#validate} before it can be sent,
 * and anything that fails falls back to the template.
 */
public interface CopyWriter {

    /** Empty means "use the template" — not an error, just no generated copy. */
    Optional<String> draft(InterventionKind kind, String locale, long amountPaise, String link);

    boolean enabled();

    /**
     * Always defers to the template. Used when no credentials are configured and
     * in backtest runs: a ten-seed sweep would spend hundreds of requests on copy
     * the backtest never scores, and copy quality is not what it measures.
     */
    final class Templates implements CopyWriter {
        @Override
        public Optional<String> draft(InterventionKind kind, String locale, long amountPaise, String link) {
            return Optional.empty();
        }

        @Override
        public boolean enabled() {
            return false;
        }
    }

    final class Gemini implements CopyWriter {

        private static final Logger log = LoggerFactory.getLogger(Gemini.class);
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final String ENDPOINT =
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

        private static final String SYSTEM_PROMPT = """
                You write short payment-failure notifications for an Indian subscription
                merchant. One or two sentences, plain and factual.

                Hard constraints:
                - The amount and any link are given to you. Reproduce them exactly.
                  Never invent, round, or reformat an amount. Never add a link.
                - No urgency pressure, no deadlines you were not given, no threat of
                  service loss beyond the plain contractual fact that the payment failed.
                - Do not use the words "immediately", "final warning", or "legal".
                - Under 320 characters.

                For locale hi-IN write natural conversational Hinglish in Latin script,
                the register an Indian bank SMS actually uses -- not formal Hindi.
                """;

        private final RestClient http;
        private final String apiKey;
        private final String model;

        public Gemini(String apiKey, String model, long timeoutMillis) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
            factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
            this.http = RestClient.builder().requestFactory(factory).build();
            this.apiKey = apiKey;
            this.model = model;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<String> draft(InterventionKind kind, String locale, long amountPaise, String link) {
            String amount = NudgeCopy.formatAmount(amountPaise);
            String instruction = """
                    Action: %s
                    Locale: %s
                    Amount (reproduce exactly): %s
                    Link (reproduce exactly, or omit if none): %s

                    Write the message text only. No preamble, no quotes.
                    """.formatted(kind, locale == null ? "en-IN" : locale, amount,
                    link == null ? "(none)" : link);

            try {
                ObjectNode root = MAPPER.createObjectNode();
                root.putObject("systemInstruction").putArray("parts")
                        .addObject().put("text", SYSTEM_PROMPT);
                root.putArray("contents").addObject().putArray("parts")
                        .addObject().put("text", instruction);
                ObjectNode generation = root.putObject("generationConfig");
                generation.put("temperature", 0);
                generation.put("maxOutputTokens", 400);

                String raw = http.post()
                        .uri(String.format(ENDPOINT, model))
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(MAPPER.writeValueAsString(root))
                        .exchange((request, response) -> new String(
                                response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));

                JsonNode parsed = MAPPER.readTree(raw);
                String text = parsed.path("candidates").path(0).path("content")
                        .path("parts").path(0).path("text").asText("");
                return text.isBlank() ? Optional.empty() : Optional.of(text.strip());
            } catch (Exception e) {
                // Copy generation must never block a decision. The template is fine.
                log.warn("Copy generation failed, using template: {}", e.toString());
                return Optional.empty();
            }
        }
    }
}

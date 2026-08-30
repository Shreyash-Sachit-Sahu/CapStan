package dev.capstan.diagnose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiagnosisConfig {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisConfig.class);

    /**
     * Tier 3 is selected by which credentials exist, not by a profile flag, so the
     * degraded path is the same code that runs when a provider is genuinely
     * unavailable. The startup log states which one is active — an eval report
     * that silently measured the stub would be worse than no report.
     *
     * <p>Two providers behind one interface is deliberate. Switching is an
     * environment variable, and "either provider, or neither, and the
     * deterministic tiers still run" is a stronger answer to Phase 09's
     * LLM-is-down question than a single hard-wired vendor.
     */
    @Bean
    LlmClassifier llmClassifier(
            @Value("${capstan.llm.gemini.api-key:}") String geminiKey,
            @Value("${capstan.llm.gemini.model:gemini-flash-lite-latest}") String geminiModel,
            @Value("${capstan.llm.anthropic.api-key:}") String anthropicKey,
            @Value("${capstan.llm.anthropic.model:claude-haiku-4-5}") String anthropicModel,
            @Value("${capstan.llm.timeout-ms:12000}") long timeoutMillis,
            @Value("${capstan.llm.min-interval-ms:6500}") long minIntervalMillis) {

        if (isSet(geminiKey)) {
            log.info("Tier 3 enabled: gemini model={} timeout={}ms minInterval={}ms",
                    geminiModel, timeoutMillis, minIntervalMillis);
            GeminiClassifier classifier =
                    new GeminiClassifier(geminiKey, geminiModel, timeoutMillis, minIntervalMillis);
            classifier.warmUp();
            return classifier;
        }

        if (isSet(anthropicKey)) {
            log.info("Tier 3 enabled: anthropic model={} timeout={}ms", anthropicModel, timeoutMillis);
            AnthropicClassifier classifier =
                    new AnthropicClassifier(anthropicKey, anthropicModel, timeoutMillis);
            classifier.warmUp();
            return classifier;
        }

        log.warn("Tier 3 DISABLED: no classifier credentials configured. "
                + "Tier 1 and Tier 2 still run; everything they cannot resolve abstains "
                + "to UNDIAGNOSED, which is not retryable.");
        return new LlmClassifier.Abstaining("tier 3 disabled: no credentials configured");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}

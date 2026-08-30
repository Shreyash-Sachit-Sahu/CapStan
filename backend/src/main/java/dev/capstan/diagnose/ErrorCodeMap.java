package dev.capstan.diagnose;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Tier 1 — deterministic lookup on Razorpay's {@code source:step:reason}.
 *
 * <p>Rules are validated at startup: an unknown cause, a malformed key, or a
 * confidence outside [0,1] fails the context rather than surfacing later as a
 * silently unmatched payload.
 */
@Component
public class ErrorCodeMap {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeMap.class);
    private static final String RESOURCE = "diagnose/rules/error_code_map.yaml";

    public record Rule(String source, String step, String reason,
                       FailureCause cause, double confidence) {

        boolean matches(String actualSource, String actualStep, String actualReason) {
            return segmentMatches(source, actualSource)
                    && segmentMatches(step, actualStep)
                    && segmentMatches(reason, actualReason);
        }

        private static boolean segmentMatches(String pattern, String actual) {
            return "*".equals(pattern) || pattern.equalsIgnoreCase(actual == null ? "" : actual);
        }
    }

    private final List<Rule> rules;

    public ErrorCodeMap() {
        this.rules = load();
        log.info("Tier 1 error code map loaded: {} rules", rules.size());
    }

    public int ruleCount() {
        return rules.size();
    }

    public Optional<Diagnosis> lookup(DiagnosisInput input) {
        for (Rule rule : rules) {
            if (rule.matches(input.rawErrorSource(), input.rawErrorStep(), input.rawErrorReason())) {
                String evidence = "reason=" + input.rawErrorReason()
                        + " matched " + rule.source() + ":" + rule.step() + ":" + rule.reason();
                return Optional.of(new Diagnosis(rule.cause(), rule.confidence(),
                        Diagnosis.DiagnosisMethod.CODE_MAP, evidence));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<Rule> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            List<Map<String, Object>> raw = new Yaml().load(in);
            if (raw == null || raw.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " is empty");
            }
            List<Rule> parsed = new ArrayList<>(raw.size());
            for (Map<String, Object> entry : raw) {
                parsed.add(toRule(entry));
            }
            return List.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    private static Rule toRule(Map<String, Object> entry) {
        String match = String.valueOf(entry.get("match"));
        String[] parts = match.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalStateException(
                    "rule key must be source:step:reason, got '" + match + "'");
        }
        String causeName = String.valueOf(entry.get("cause"));
        FailureCause cause = FailureCause.parse(causeName).orElseThrow(() ->
                new IllegalStateException("rule '" + match + "' maps to unknown cause '"
                        + causeName + "'; valid values: " + List.of(FailureCause.values())));
        double confidence = ((Number) entry.get("confidence")).doubleValue();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalStateException(
                    "rule '" + match + "' has confidence outside [0,1]: " + confidence);
        }
        return new Rule(parts[0], parts[1], parts[2], cause, confidence);
    }
}

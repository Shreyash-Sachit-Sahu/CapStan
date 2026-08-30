package dev.capstan.diagnose;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Tier 2 — regex over the bank narration, tried when Tier 1 has no match.
 *
 * <p>Covers standard bank shorthand only. Conversational and Hinglish phrasings
 * are left to Tier 3 on purpose; see narration_rules.yaml for why.
 *
 * <p>Rules are compiled and validated at startup, so a bad pattern fails the
 * context instead of throwing on the first payload that reaches it.
 */
@Component
public class NarrationRules {

    private static final Logger log = LoggerFactory.getLogger(NarrationRules.class);
    private static final String RESOURCE = "diagnose/rules/narration_rules.yaml";

    public record Rule(Pattern pattern, FailureCause cause, double confidence) {
    }

    private final List<Rule> rules;

    public NarrationRules() {
        this.rules = load();
        log.info("Tier 2 narration rules loaded: {} rules", rules.size());
    }

    public int ruleCount() {
        return rules.size();
    }

    public Optional<Diagnosis> lookup(DiagnosisInput input) {
        String narration = input.bankNarration();
        if (narration == null || narration.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : rules) {
            var matcher = rule.pattern().matcher(narration);
            if (matcher.find()) {
                String evidence = "narration matched '" + matcher.group() + "'";
                return Optional.of(new Diagnosis(rule.cause(), rule.confidence(),
                        Diagnosis.DiagnosisMethod.NARRATION, evidence));
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
                String source = String.valueOf(entry.get("pattern"));
                String causeName = String.valueOf(entry.get("cause"));
                FailureCause cause = FailureCause.parse(causeName).orElseThrow(() ->
                        new IllegalStateException("narration rule '" + source
                                + "' maps to unknown cause '" + causeName + "'"));
                double confidence = ((Number) entry.get("confidence")).doubleValue();
                if (confidence < 0.0 || confidence > 1.0) {
                    throw new IllegalStateException("narration rule '" + source
                            + "' has confidence outside [0,1]: " + confidence);
                }
                try {
                    parsed.add(new Rule(Pattern.compile(source), cause, confidence));
                } catch (PatternSyntaxException e) {
                    throw new IllegalStateException("narration rule '" + source
                            + "' is not a valid regex", e);
                }
            }
            return List.copyOf(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}

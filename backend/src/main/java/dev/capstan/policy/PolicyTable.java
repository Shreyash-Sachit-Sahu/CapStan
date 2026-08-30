package dev.capstan.policy;

import dev.capstan.diagnose.FailureCause;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * The policy table, loaded once and immutable thereafter.
 *
 * <p>Validated hard at startup: every cause except UNDIAGNOSED must have a
 * ladder, every ladder entry must be a real {@link InterventionKind}, and a
 * ladder containing a debit must permit at least one. That last check catches
 * dead configuration — a ladder whose retry can never fire because the cap is
 * zero looks correct in review and does nothing in production.
 */
@Component
public class PolicyTable {

    private static final Logger log = LoggerFactory.getLogger(PolicyTable.class);
    private static final String RESOURCE = "policy/recovery_policy.yaml";

    public record Policy(
            String name,
            List<InterventionKind> ladder,
            Map<InterventionKind, String> schedule,
            int maxDebitAttempts,
            Integer maxNudges,
            String terminalReason,
            String note) {

        public String scheduleFor(InterventionKind kind) {
            return schedule.get(kind);
        }
    }

    private final int version;
    private final Map<FailureCause, Policy> byCause;
    private final Policy lowConfidence;
    private final Policy undiagnosedCleared;
    private final Policy undiagnosedUncleared;

    @SuppressWarnings("unchecked")
    public PolicyTable() {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
        if (root == null) {
            throw new IllegalStateException(RESOURCE + " is empty");
        }

        this.version = ((Number) root.get("version")).intValue();
        this.byCause = new EnumMap<>(FailureCause.class);

        for (Map<String, Object> entry : (List<Map<String, Object>>) root.get("policies")) {
            String causeName = String.valueOf(entry.get("cause"));
            FailureCause cause = FailureCause.parse(causeName).orElseThrow(() ->
                    new IllegalStateException("policy for unknown cause '" + causeName + "'"));
            if (cause == FailureCause.UNDIAGNOSED) {
                throw new IllegalStateException(
                        "UNDIAGNOSED must not have a ladder; it is handled by the clearance branch");
            }
            byCause.put(cause, toPolicy(causeName, entry));
        }

        for (FailureCause cause : FailureCause.values()) {
            if (cause != FailureCause.UNDIAGNOSED && !byCause.containsKey(cause)) {
                throw new IllegalStateException("no policy defined for cause " + cause);
            }
        }

        this.lowConfidence = toPolicy("low_confidence",
                (Map<String, Object>) root.get("low_confidence"));
        this.undiagnosedCleared = toPolicy("undiagnosed_cleared",
                (Map<String, Object>) root.get("undiagnosed_cleared"));
        this.undiagnosedUncleared = toPolicy("undiagnosed_uncleared",
                (Map<String, Object>) root.get("undiagnosed_uncleared"));

        if (undiagnosedUncleared.maxDebitAttempts() != 0) {
            throw new IllegalStateException(
                    "undiagnosed_uncleared must never permit a debit; not knowing is not permission");
        }

        log.info("Policy table v{} loaded: {} causes + low-confidence and UNDIAGNOSED branches",
                version, byCause.size());
    }

    @SuppressWarnings("unchecked")
    private static Policy toPolicy(String name, Map<String, Object> entry) {
        if (entry == null) {
            throw new IllegalStateException("missing policy block '" + name + "'");
        }

        List<InterventionKind> ladder = new ArrayList<>();
        for (Object raw : (List<Object>) entry.get("ladder")) {
            ladder.add(parseKind(String.valueOf(raw), name));
        }
        if (ladder.isEmpty()) {
            throw new IllegalStateException("policy '" + name + "' has an empty ladder");
        }

        Map<InterventionKind, String> schedule = new LinkedHashMap<>();
        Map<String, Object> rawSchedule = (Map<String, Object>) entry.get("schedule");
        if (rawSchedule != null) {
            rawSchedule.forEach((k, v) -> schedule.put(parseKind(k, name), String.valueOf(v)));
        }

        int maxDebitAttempts = entry.get("max_debit_attempts") == null
                ? 0 : ((Number) entry.get("max_debit_attempts")).intValue();

        boolean ladderHasDebit = ladder.stream().anyMatch(k -> k.isDebit);
        if (ladderHasDebit && maxDebitAttempts == 0) {
            throw new IllegalStateException("policy '" + name + "' has a debit in its ladder "
                    + "but max_debit_attempts=0, so the debit can never fire");
        }

        Integer maxNudges = entry.get("max_nudges") == null
                ? null : ((Number) entry.get("max_nudges")).intValue();

        return new Policy(name, List.copyOf(ladder), Map.copyOf(schedule), maxDebitAttempts,
                maxNudges,
                entry.get("terminal_reason") == null ? null : String.valueOf(entry.get("terminal_reason")),
                entry.get("note") == null ? null : String.valueOf(entry.get("note")));
    }

    private static InterventionKind parseKind(String raw, String policyName) {
        try {
            return InterventionKind.valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "policy '" + policyName + "' names unknown action '" + raw + "'");
        }
    }

    public int version() {
        return version;
    }

    public Policy forCause(FailureCause cause) {
        Policy policy = byCause.get(cause);
        if (policy == null) {
            throw new IllegalStateException("no policy for cause " + cause);
        }
        return policy;
    }

    public Policy lowConfidence() {
        return lowConfidence;
    }

    public Policy undiagnosedCleared() {
        return undiagnosedCleared;
    }

    public Policy undiagnosedUncleared() {
        return undiagnosedUncleared;
    }
}

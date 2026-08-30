package dev.capstan.backtest;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.DiagnosisInput;
import dev.capstan.diagnose.DiagnosisService;
import dev.capstan.diagnose.FailureCause;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Scores the diagnosis cascade against {@code case_oracle.true_cause}.
 *
 * <p>Lives in {@code dev.capstan.backtest} because it reads the oracle. The
 * cascade itself never sees a row from this query — the evaluator hands it only
 * a {@link DiagnosisInput}, and compares afterwards.
 */
@Service
@RequiredArgsConstructor
public class DiagnosisEvaluator {

    /** A RISK_BLOCKED read as retryable is not a mistake, it is a safety failure. */
    static final double SEVERITY_SAFETY = 10.0;
    /** A dead case read as retryable burns real debit attempts. */
    private static final double SEVERITY_WASTED_DEBITS = 3.0;
    private static final double SEVERITY_ORDINARY = 1.0;

    private final JdbcClient jdbc;
    private final DiagnosisService diagnosisService;

    public record TierStat(int cases, double coverage, int correct, double accuracy) {
    }

    public record Confusion(String trueCause, String predicted, int count) {
    }

    public record EvalReport(
            String batch,
            int cases,
            double overall,
            Map<String, TierStat> perTier,
            double abstentionRate,
            double accuracyOnNonAbstained,
            int safetyFailures,
            double severityWeightedErrorRate,
            List<Confusion> topConfusions,
            Map<String, Map<String, Integer>> confusionMatrix,
            DiagnosisService.Stats runtime) {
    }

    private record ScoredRow(DiagnosisService.CaseRow row, FailureCause trueCause) {
    }

    public EvalReport evaluate(String batch) {
        diagnosisService.resetStats();
        List<ScoredRow> rows = load(batch);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no cases loaded for batch '" + batch + "'");
        }

        Map<String, int[]> perTierCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> matrix = emptyMatrix();
        int correct = 0;
        int abstained = 0;
        int correctNonAbstained = 0;
        int safetyFailures = 0;
        double severitySum = 0.0;

        for (ScoredRow scored : rows) {
            Diagnosis diagnosis = diagnosisService.classify(scored.row());
            FailureCause truth = scored.trueCause();
            FailureCause predicted = diagnosis.cause();
            boolean hit = truth == predicted;

            matrix.get(truth.name()).merge(predicted.name(), 1, Integer::sum);

            int[] tier = perTierCounts.computeIfAbsent(diagnosis.method().name(), k -> new int[2]);
            tier[0]++;
            if (hit) {
                tier[1]++;
                correct++;
            }

            if (diagnosis.method() == Diagnosis.DiagnosisMethod.ABSTAIN) {
                abstained++;
            } else if (hit) {
                correctNonAbstained++;
            }

            if (!hit) {
                double severity = severityOf(truth, predicted);
                severitySum += severity;
                if (severity == SEVERITY_SAFETY) {
                    safetyFailures++;
                }
            }
        }

        int n = rows.size();
        Map<String, TierStat> perTier = new LinkedHashMap<>();
        perTierCounts.forEach((method, counts) -> perTier.put(method, new TierStat(
                counts[0], round((double) counts[0] / n), counts[1],
                counts[0] == 0 ? 0.0 : round((double) counts[1] / counts[0]))));

        int nonAbstained = n - abstained;
        return new EvalReport(
                batch,
                n,
                round((double) correct / n),
                perTier,
                round((double) abstained / n),
                nonAbstained == 0 ? 0.0 : round((double) correctNonAbstained / nonAbstained),
                safetyFailures,
                round(severitySum / (n * SEVERITY_SAFETY)),
                topConfusions(matrix),
                matrix,
                diagnosisService.stats());
    }

    /**
     * Not all errors cost the same. Retrying a risk-blocked debit is the
     * behaviour the safety bar calls out; confusing two retryable causes costs
     * one wasted attempt. Reporting a single accuracy number hides that.
     */
    static double severityOf(FailureCause truth, FailureCause predicted) {
        if (truth == predicted) {
            return 0.0;
        }
        if (truth == FailureCause.RISK_BLOCKED && predicted.retryable) {
            return SEVERITY_SAFETY;
        }
        if (!truth.retryable && predicted.retryable) {
            return SEVERITY_WASTED_DEBITS;
        }
        return SEVERITY_ORDINARY;
    }

    private static Map<String, Map<String, Integer>> emptyMatrix() {
        Map<String, Map<String, Integer>> matrix = new TreeMap<>();
        for (FailureCause truth : FailureCause.values()) {
            Map<String, Integer> row = new TreeMap<>();
            for (FailureCause predicted : FailureCause.values()) {
                row.put(predicted.name(), 0);
            }
            matrix.put(truth.name(), row);
        }
        return matrix;
    }

    private static List<Confusion> topConfusions(Map<String, Map<String, Integer>> matrix) {
        List<Confusion> confusions = new ArrayList<>();
        matrix.forEach((truth, row) -> row.forEach((predicted, count) -> {
            if (count > 0 && !truth.equals(predicted)) {
                confusions.add(new Confusion(truth, predicted, count));
            }
        }));
        confusions.sort(Comparator.comparingInt(Confusion::count).reversed());
        return confusions.size() > 8 ? confusions.subList(0, 8) : confusions;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private List<ScoredRow> load(String batch) {
        return jdbc.sql("""
                select c.id, c.raw_error_code, c.raw_error_reason, c.raw_error_desc,
                       c.raw_error_source, c.raw_error_step, c.bank_narration,
                       c.first_failed_at, m.rail, m.valid_until, o.true_cause
                  from recovery_case c
                  join mandate m on m.id = c.mandate_id
                  join case_oracle o on o.case_id = c.id
                 where c.batch_label = :batch
                 order by c.id
                """)
                .param("batch", batch)
                .query(DiagnosisEvaluator::mapRow)
                .list();
    }

    private static ScoredRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        DiagnosisInput input = new DiagnosisInput(
                rs.getString("raw_error_code"),
                rs.getString("raw_error_reason"),
                rs.getString("raw_error_desc"),
                rs.getString("raw_error_source"),
                rs.getString("raw_error_step"),
                rs.getString("bank_narration"),
                rs.getString("rail"));
        DiagnosisService.CaseRow row = new DiagnosisService.CaseRow(
                rs.getObject("id", UUID.class),
                input,
                toInstant(rs.getObject("valid_until", OffsetDateTime.class)),
                toInstant(rs.getObject("first_failed_at", OffsetDateTime.class)));
        String rawTrueCause = rs.getString("true_cause");
        FailureCause truth = FailureCause.parse(rawTrueCause).orElseThrow(() ->
                new IllegalStateException("oracle holds unknown true_cause: " + rawTrueCause));
        return new ScoredRow(row, truth);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

package dev.capstan.backtest;

import dev.capstan.backtest.BacktestArm.ArmMetrics;
import dev.capstan.backtest.BacktestArm.RunContext;
import dev.capstan.diagnose.DiagnosisService;
import dev.capstan.gateway.SimulatedGateway;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Runs the arms, keeps them isolated, and produces the report. */
@Service
@RequiredArgsConstructor
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    private final JdbcClient jdbc;
    private final DiagnosisService diagnosis;
    private final CapstanArm capstan;
    private final FixedLadderBaseline baseline;
    private final OracleUpperBound upperBound;
    private final SimulatedGateway gateway;
    private final ReportStore reports;

    public record Report(String batch, int cases, long atRiskPaise, int policyVersion,
                         String inject, Map<String, ArmMetrics> arms) {
    }

    public record SweepReport(List<String> batches, Map<String, Double> median,
                              Map<String, Double> iqr, List<Report> runs) {
    }

    public record AblationReport(String batch, double baselineRatePaise, double capstanRatePaise,
                                 double liftPp, Map<String, Double> attributionPp,
                                 Map<String, ArmMetrics> runs) {
    }

    // ---------------------------------------------------------------- preparation

    /** Diagnoses every case in the batch that has no cause yet. */
    public int prepare(String batch) {
        List<UUID> undiagnosed = jdbc.sql("""
                select id from recovery_case
                 where batch_label = :label and diagnosed_cause is null
                 order by id
                """)
                .param("label", batch).query(UUID.class).list();

        for (UUID caseId : undiagnosed) {
            diagnosis.diagnoseAndPersist(caseId);
        }
        return undiagnosed.size();
    }

    /**
     * Asserts rather than assumes that diagnosis already ran.
     *
     * <p>If it has not, every tick of every arm of every seed would classify on
     * demand — and Tier 3's throttle is wall-clock at four seconds a call, by
     * design, because it is a real API limit rather than simulated time. A ten
     * seed sweep would turn into an hours-long grind that also burns the daily
     * quota. Loud and immediate beats slow and mysterious.
     */
    private void requireDiagnosed(String batch) {
        Integer missing = jdbc.sql("""
                select count(*) from recovery_case
                 where batch_label = :label and diagnosed_cause is null
                """).param("label", batch).query(Integer.class).single();

        if (missing == null || missing > 0) {
            throw new IllegalStateException(
                    "batch '" + batch + "' has " + missing + " undiagnosed case(s). "
                            + "Run POST /api/backtest/prepare?batch=" + batch + " first — "
                            + "diagnosing inside the run would hit the Tier 3 rate limit "
                            + "on every tick.");
        }
    }

    // ---------------------------------------------------------------- running

    public Report run(String batch, List<String> arms, Ablation ablation, String inject) {
        requireDiagnosed(batch);

        List<UUID> caseIds = caseIds(batch);
        if (caseIds.isEmpty()) {
            throw new IllegalArgumentException("no cases loaded for batch '" + batch + "'");
        }
        Map<UUID, Long> amounts = amounts(batch);
        Map<UUID, Instant> firstFailed = firstFailedAt(batch);
        Window window = window(batch);
        long atRisk = amounts.values().stream().mapToLong(Long::longValue).sum();

        Map<String, ArmMetrics> results = new LinkedHashMap<>();

        if (arms.contains("baseline")) {
            resetGateway(inject);
            baseline.run(new RunContext(batch, caseIds,
                    new VirtualClock(window.start(), window.end()), gateway, Ablation.NONE));
            results.put("baseline", BacktestArm.measure("baseline", caseIds, amounts, firstFailed,
                    gateway, baseline.commsSent(), Map.of(),
                    Map.of("RECOVERED", 0, "NOT_RECOVERED", 0), 0));
        }

        if (arms.contains("capstan")) {
            resetGateway(inject);
            resetCapstanState(batch);
            capstan.run(new RunContext(batch, caseIds,
                    new VirtualClock(window.start(), window.end()), gateway, ablation));
            results.put("capstan", BacktestArm.measure("capstan", caseIds, amounts, firstFailed,
                    gateway, commsSent(batch), guardrailBlocks(batch),
                    terminalBreakdown(batch), escalated(batch)));
        }

        if (arms.contains("upperBound")) {
            results.put("upperBound", upperBound.compute(caseIds, amounts, firstFailed));
        }

        Report report = new Report(batch, caseIds.size(), atRisk, 1,
                inject == null ? "none" : inject, results);
        // An equal-budget run is a different measurement from an unconstrained one
        // and must not overwrite it.
        int budget = injectedBudget(inject);
        if (ablation == Ablation.NONE) {
            reports.save(budget > 0 ? "budget" : "run", batch,
                    budget > 0 ? String.valueOf(budget) : "", report);
        }
        return report;
    }

    public SweepReport sweep(List<String> batches, String inject) {
        List<Report> runs = new ArrayList<>();
        for (String batch : batches) {
            runs.add(run(batch, List.of("baseline", "capstan", "upperBound"), Ablation.NONE, inject));
        }
        Map<String, Double> median = new LinkedHashMap<>();
        Map<String, Double> iqr = new LinkedHashMap<>();
        for (String arm : List.of("baseline", "capstan", "upperBound")) {
            List<Double> values = runs.stream()
                    .map(r -> r.arms().get(arm))
                    .filter(java.util.Objects::nonNull)
                    .map(ArmMetrics::recoveryRatePaise)
                    .sorted().toList();
            if (values.isEmpty()) {
                continue;
            }
            median.put(arm, quantile(values, 0.5));
            iqr.put(arm, quantile(values, 0.75) - quantile(values, 0.25));
        }
        SweepReport sweep = new SweepReport(batches, median, iqr, runs);
        reports.save("sweep", "*", "", sweep);
        return sweep;
    }

    /**
     * Each mechanism disabled in turn. The attribution is the difference each one
     * makes, which is a stronger claim than a single lift number because it says
     * which decision earned which rupee.
     */
    public AblationReport ablations(String batch, String inject) {
        Report reference = run(batch, List.of("baseline", "capstan"), Ablation.NONE, inject);
        double baseRate = reference.arms().get("baseline").recoveryRatePaise();
        double fullRate = reference.arms().get("capstan").recoveryRatePaise();

        Map<String, ArmMetrics> runs = new LinkedHashMap<>();
        Map<String, Double> attribution = new LinkedHashMap<>();
        runs.put("full", reference.arms().get("capstan"));

        for (Ablation ablation : Ablation.values()) {
            if (ablation == Ablation.NONE) {
                continue;
            }
            ArmMetrics without = run(batch, List.of("capstan"), ablation, inject)
                    .arms().get("capstan");
            runs.put(ablation.label(), without);
            // What the mechanism was worth: what we lose without it.
            attribution.put(ablation.label(),
                    round((fullRate - without.recoveryRatePaise()) * 100));
        }
        AblationReport report = new AblationReport(batch, round(baseRate * 100),
                round(fullRate * 100), round((fullRate - baseRate) * 100), attribution, runs);
        reports.save("ablations", batch, "", report);
        return report;
    }

    // ---------------------------------------------------------------- state

    private void resetGateway(String inject) {
        // reset() clears faults, so injection has to follow it -- otherwise the
        // first arm runs with faults and the rest run clean.
        gateway.reset();
        if (inject != null && !inject.isBlank()) {
            gateway.inject(SimulatedGateway.Faults.parse(inject));
        }
    }

    /**
     * Returns <b>every</b> loaded batch to its post-diagnosis state, not just the
     * one under test.
     *
     * <p>The wider scope is not tidiness. G11, the issuer circuit breaker, counts
     * recent attempts sharing a {@code raw_error_reason} across all cases — there
     * is one payment stream in production, so it has no batch filter and should
     * not grow one. But every batch is generated over the same virtual dates, so
     * another batch's leftover attempts sit inside G11's lookback window and trip
     * the breaker continuously.
     *
     * <p>Measured: running {@code v1} with the holdout's attempts still present
     * fired G11 <b>371</b> times and pushed recovery from 32.2% to 9.6%. The
     * baseline was unaffected, because it consults no cross-case state — which is
     * exactly why the discrepancy was visible at all. A backtest has to own the
     * database.
     */
    public void resetCapstanState(String batch) {
        clearExecutionState();
    }

    private void clearExecutionState() {
        jdbc.sql("alter table audit_event disable trigger trg_audit_no_update").update();
        try {
            jdbc.sql("delete from audit_event where event_type <> 'CASE_OPENED'").update();
        } finally {
            jdbc.sql("alter table audit_event enable trigger trg_audit_no_update").update();
        }
        jdbc.sql("delete from payment_attempt").update();
        jdbc.sql("delete from outbox").update();
        jdbc.sql("delete from intervention").update();
        jdbc.sql("""
                update recovery_case
                   set status = case when diagnosed_cause is null then 'OPEN' else 'DIAGNOSED' end,
                       terminal_reason = null,
                       updated_at = first_failed_at
                 where batch_label is not null
                """).update();
    }

    // ---------------------------------------------------------------- reads

    private record Window(Instant start, Instant end) {
    }

    private Window window(String batch) {
        return jdbc.sql("""
                select min(first_failed_at) as s, max(billing_cycle_end) as e
                  from recovery_case where batch_label = :label
                """)
                .param("label", batch)
                .query((rs, rowNum) -> new Window(
                        rs.getObject("s", OffsetDateTime.class).toInstant(),
                        rs.getObject("e", OffsetDateTime.class).toInstant()))
                .single();
    }

    private List<UUID> caseIds(String batch) {
        return jdbc.sql("select id from recovery_case where batch_label = :label order by id")
                .param("label", batch).query(UUID.class).list();
    }

    private Map<UUID, Long> amounts(String batch) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        jdbc.sql("select id, amount_paise from recovery_case where batch_label = :label")
                .param("label", batch)
                .query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class),
                        rs.getLong("amount_paise")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private Map<UUID, Instant> firstFailedAt(String batch) {
        Map<UUID, Instant> out = new LinkedHashMap<>();
        jdbc.sql("select id, first_failed_at from recovery_case where batch_label = :label")
                .param("label", batch)
                .query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class),
                        rs.getObject("first_failed_at", OffsetDateTime.class).toInstant()))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private int commsSent(String batch) {
        return jdbc.sql("""
                select count(*) from intervention i join recovery_case c on c.id = i.case_id
                 where c.batch_label = :label and i.outcome = 'SENT'
                """).param("label", batch).query(Integer.class).single();
    }

    private Map<String, Integer> guardrailBlocks(String batch) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("""
                select e.payload->>'guardrailId' as id, count(*) as n
                  from audit_event e join recovery_case c on c.id = e.case_id
                 where c.batch_label = :label
                   and e.event_type in ('GUARDRAIL_BLOCKED', 'COMMS_SUPPRESSED')
                 group by 1 order by 1
                """).param("label", batch)
                .query((rs, rowNum) -> Map.entry(String.valueOf(rs.getString("id")), rs.getInt("n")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private Map<String, Integer> terminalBreakdown(String batch) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("""
                select status, count(*) as n from recovery_case
                 where batch_label = :label group by 1 order by 1
                """).param("label", batch)
                .query((rs, rowNum) -> Map.entry(rs.getString("status"), rs.getInt("n")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private int escalated(String batch) {
        return jdbc.sql("""
                select count(*) from recovery_case
                 where batch_label = :label and status = 'ESCALATED'
                """).param("label", batch).query(Integer.class).single();
    }

    private static double quantile(List<Double> sorted, double q) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    /** Reads max_debits_per_case back out of the inject spec, 0 when absent. */
    private static int injectedBudget(String inject) {
        if (inject == null) {
            return 0;
        }
        for (String part : inject.split(",")) {
            String[] kv = part.trim().split("[:=]", 2);
            if (kv.length == 2 && kv[0].trim().equals("max_debits_per_case")) {
                return Integer.parseInt(kv[1].trim());
            }
        }
        return 0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

package dev.capstan.backtest;

import dev.capstan.gateway.SimulatedGateway;
import dev.capstan.gateway.SimulatedGateway.Movement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One policy, run over one batch, against the shared oracle and the shared
 * simulated gateway.
 *
 * <p><b>The gateway is the measurement surface, and that is what makes the
 * comparison fair.</b> Whether a case recovered, how many times money actually
 * moved, whether an attempt was structurally doomed, and whether a risk-blocked
 * customer was debited are all read off {@link SimulatedGateway} movements — not
 * off each arm's own bookkeeping. An arm cannot flatter itself by counting
 * differently, because it does not do the counting.
 *
 * <p>The arms are implemented differently on purpose. Capstan runs the real
 * pipeline through the database; the baseline is an in-memory ladder, because a
 * baseline routed through Capstan's execution machinery would inherit
 * reconciliation, which §2 specifies it must not have.
 */
public interface BacktestArm {

    String name();

    /** Runs the whole cycle. Metrics are derived afterwards from the gateway. */
    void run(RunContext ctx);

    record RunContext(String batchLabel, List<UUID> caseIds, VirtualClock clock,
                      SimulatedGateway gateway, Ablation ablation) {
    }

    /**
     * @param wastedAttempts        debits the oracle says could not have succeeded at
     *                              that instant for a structural reason
     * @param safetyFailures        debits against a customer whose true cause is
     *                              RISK_BLOCKED. A safety difference, not a
     *                              recovery one, and it belongs next to the money.
     * @param duplicateChargesCaused cases where money moved successfully more
     *                              than once
     */
    record ArmMetrics(
            String arm,
            int cases,
            int recoveredCases, double recoveryRate,
            long atRiskPaise, long recoveredPaise, double recoveryRatePaise,
            int debitAttempts, int wastedAttempts, double wastedAttemptRate,
            int commsSent, Double commsPerRecovery,
            int duplicateChargesCaused,
            int safetyFailures,
            Double meanTimeToRecoveryHours,
            Map<String, Integer> guardrailBlocks,
            Map<String, Integer> terminalBreakdown,
            int escalatedToHuman) {
    }

    /**
     * Derives every comparable metric from the gateway, so both arms are scored
     * the same way by the same code.
     */
    static ArmMetrics measure(String arm, List<UUID> caseIds, Map<UUID, Long> amounts,
                              Map<UUID, Instant> firstFailedAt, SimulatedGateway gateway,
                              int commsSent, Map<String, Integer> guardrailBlocks,
                              Map<String, Integer> terminalBreakdown, int escalated) {
        int recovered = 0;
        long recoveredPaise = 0;
        long atRisk = 0;
        int attempts = 0;
        int wasted = 0;
        int duplicates = 0;
        int safety = 0;
        List<Double> recoveryHours = new ArrayList<>();

        for (UUID caseId : caseIds) {
            atRisk += amounts.getOrDefault(caseId, 0L);
            List<Movement> movements = gateway.movementsFor(caseId);
            attempts += movements.size();

            int succeeded = 0;
            Instant firstSuccess = null;
            for (Movement movement : movements) {
                if (movement.doomed()) {
                    wasted++;
                }
                if (movement.riskBlocked()) {
                    safety++;
                }
                if (movement.outcome() == SimulatedGateway.Outcome.SUCCEEDED) {
                    succeeded++;
                    if (firstSuccess == null) {
                        firstSuccess = movement.at();
                    }
                }
            }
            if (succeeded > 0) {
                recovered++;
                recoveredPaise += amounts.getOrDefault(caseId, 0L);
                Instant failedAt = firstFailedAt.get(caseId);
                if (failedAt != null) {
                    recoveryHours.add(Duration.between(failedAt, firstSuccess).toMinutes() / 60.0);
                }
            }
            if (succeeded > 1) {
                duplicates += succeeded - 1;
            }
        }

        int n = caseIds.size();
        return new ArmMetrics(
                arm, n,
                recovered, n == 0 ? 0 : (double) recovered / n,
                atRisk, recoveredPaise, atRisk == 0 ? 0 : (double) recoveredPaise / atRisk,
                attempts, wasted, attempts == 0 ? 0 : (double) wasted / attempts,
                commsSent, recovered == 0 ? null : (double) commsSent / recovered,
                duplicates,
                safety,
                recoveryHours.isEmpty() ? null
                        : recoveryHours.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                new TreeMap<>(guardrailBlocks),
                new LinkedHashMap<>(terminalBreakdown),
                escalated);
    }
}

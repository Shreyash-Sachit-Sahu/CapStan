package dev.capstan.backtest;

import dev.capstan.backtest.BacktestArm.ArmMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The ceiling: what any policy could achieve with perfect knowledge.
 *
 * <p>Deliberately <b>not</b> an executable arm. It makes no gateway calls and it
 * is not a policy anyone could run — it is a bound, computed straight from the
 * oracle: every case the oracle marks recoverable is recovered, at the first
 * instant inside its window, through whichever channel it requires.
 *
 * <p>Reporting it is a confident move rather than a modest one. It says we know
 * what we did not capture, and it pre-empts the only interesting question about
 * any recovery number: compared to what maximum? Attempt and comms counts are
 * reported as zero because the bound does not spend them — treating those cells
 * as achievable would be the dishonest version.
 */
@Service
@RequiredArgsConstructor
public class OracleUpperBound {

    private final OracleOutcomeResolver oracle;

    public ArmMetrics compute(List<UUID> caseIds, Map<UUID, Long> amounts,
                              Map<UUID, Instant> firstFailedAt) {
        int recoverable = 0;
        long recoveredPaise = 0;
        long atRisk = 0;
        double totalHours = 0;
        int timed = 0;

        for (UUID caseId : caseIds) {
            atRisk += amounts.getOrDefault(caseId, 0L);
            var truth = oracle.truthFor(caseId);
            if (truth.isEmpty() || !truth.get().recoverable()) {
                continue;
            }
            recoverable++;
            recoveredPaise += amounts.getOrDefault(caseId, 0L);

            Instant failedAt = firstFailedAt.get(caseId);
            Instant earliest = truth.get().recoveryWindowStart();
            if (failedAt != null) {
                Instant at = earliest == null || earliest.isBefore(failedAt) ? failedAt : earliest;
                totalHours += Duration.between(failedAt, at).toMinutes() / 60.0;
                timed++;
            }
        }

        int n = caseIds.size();
        Map<String, Integer> terminal = new LinkedHashMap<>();
        terminal.put("RECOVERED", recoverable);
        terminal.put("UNRECOVERABLE", n - recoverable);

        return new ArmMetrics(
                "upperBound", n,
                recoverable, n == 0 ? 0 : (double) recoverable / n,
                atRisk, recoveredPaise, atRisk == 0 ? 0 : (double) recoveredPaise / atRisk,
                0, 0, 0.0,
                0, null,
                0,
                0,
                timed == 0 ? null : totalHours / timed,
                Map.of(),
                terminal,
                0);
    }
}

package dev.capstan.backtest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The harness. Lives in dev.capstan.backtest because it reads the oracle. */
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestRunner runner;
    private final ExceptionList exceptions;

    /** Diagnoses the batch once, so no run ever classifies on the clock. */
    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestParam String batch) {
        return Map.of("batch", batch, "diagnosed", runner.prepare(batch));
    }

    @PostMapping("/run")
    public BacktestRunner.Report run(
            @RequestParam(defaultValue = "v1") String batch,
            @RequestParam(defaultValue = "baseline,capstan,upperBound") String arms,
            @RequestParam(required = false) String inject) {
        return runner.run(batch, Arrays.asList(arms.split(",")), Ablation.NONE, inject);
    }

    /**
     * @param batches ten distinct fixtures, not ten replays. The simulator is
     *                deterministic per seed and the gateway derives every roll
     *                from the idempotency key, so re-running one batch ten times
     *                would report an IQR of zero and imply variance had been
     *                measured when it had not.
     */
    @PostMapping("/sweep")
    public BacktestRunner.SweepReport sweep(
            @RequestParam String batches,
            @RequestParam(required = false) String inject) {
        List<String> labels = Arrays.asList(batches.split(","));
        return runner.sweep(labels, inject);
    }

    @PostMapping("/ablations")
    public BacktestRunner.AblationReport ablations(
            @RequestParam(defaultValue = "v1") String batch,
            @RequestParam(required = false) String inject) {
        return runner.ablations(batch, inject);
    }

    @GetMapping("/exceptions")
    public Map<String, Object> exceptions(
            @RequestParam(defaultValue = "v1") String batch,
            @RequestParam(defaultValue = "true") boolean trueCause) {
        return exceptions.forBatch(batch, trueCause);
    }
}

package dev.capstan.backtest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class DiagnosisEvalController {

    private final DiagnosisEvaluator evaluator;

    @GetMapping("/diagnosis")
    public DiagnosisEvaluator.EvalReport diagnosis(
            @RequestParam(name = "batch", defaultValue = "v1") String batch) {
        return evaluator.evaluate(batch);
    }
}

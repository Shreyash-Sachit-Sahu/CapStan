package dev.capstan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The submission documents carry claims the code cannot make for itself, and
 * restructuring is exactly when one of them disappears.
 *
 * <p>This is not hypothetical. Reordering the README for skimming silently
 * deleted the oracle-disclosure section — the load-bearing answer to "how do we
 * know this is not rigged" — along with the per-tier accuracy table. It was
 * caught by eye before the commit, which is not a control.
 *
 * <p>So the sections that cannot afford to go missing fail the build instead.
 */
class SubmissionDocsTest {

    private static final Path README = Path.of("..", "README.md");
    private static final Path DEMO = Path.of("..", "docs", "DEMO.md");

    /** Each entry: a heading that must exist, and why losing it would matter. */
    private static final List<String[]> REQUIRED_README = List.of(
            new String[]{"## Result",
                    "the headline table a judge reads first"},
            new String[]{"## What we got wrong, and how we know",
                    "the audit section; the differentiator, not an appendix"},
            new String[]{"## How we know the numbers aren't cheating",
                    "the oracle disclosure — the answer to 'is this rigged'"},
            new String[]{"## Where the AI actually is",
                    "the per-tier accuracy table; where the model ends"},
            new String[]{"## What the model is not allowed to do",
                    "the model cannot reach a decision path"},
            new String[]{"## Why a lost response is not a double charge",
                    "the exactly-once claim and what enforces it"},
            new String[]{"## The audit trail records what we didn't do",
                    "suppressed actions are the boundedness evidence"},
            new String[]{"## Run it",
                    "a submission nobody can start is not a submission"});

    @Test
    void theReadmeKeepsEverySectionItCannotAffordToLose() throws IOException {
        String readme = read(README);
        for (String[] required : REQUIRED_README) {
            assertThat(readme)
                    .as("README is missing '%s' — %s", required[0], required[1])
                    .contains(required[0]);
        }
    }

    /**
     * A stale figure in the demo script is worse than none: the screen behind the
     * presenter contradicts them, live. These are the numbers that changed most
     * recently, so they are the ones most likely to be left behind.
     */
    @Test
    void theDemoScriptCarriesNoSupersededFigures() throws IOException {
        String demo = read(DEMO);
        List<String[]> superseded = List.of(
                new String[]{"41.8 lakh", "at-risk figure from the original brief"},
                new String[]{"G1–G11", "there are twelve guardrails"},
                new String[]{"double-charged six", "the measured count is 33 across the sweep"},
                new String[]{"Nineteen recoverable", "the measured miss count is 67"},
                new String[]{"queued retry cancelled",
                        "no cancelled intervention exists in backtest data — Phase 05 §6"});

        for (String[] stale : superseded) {
            assertThat(demo)
                    .as("DEMO.md still contains '%s' — %s", stale[0], stale[1])
                    .doesNotContain(stale[0]);
        }
    }

    @Test
    void theDemoScriptNeverDerivesTheTamperIdLive() throws IOException {
        // A subshell on stage is one empty result away from a blank error during
        // the five seconds that carry the most effect. The id is captured in
        // pre-flight and pasted.
        String demo = read(DEMO);
        assertThat(demo)
                .as("the tamper beat must use a pre-captured id, not a live subshell")
                .doesNotContain("tamper/$(");
        assertThat(demo).contains("<TAMPER_EVENT_ID>");
    }


    /**
     * The one-pager is the artifact that gets forwarded to someone who was never
     * in the room, so it is the one document nobody will notice has gone stale.
     * Re-run the sweep, update the README, forget this file, and a wrong number
     * travels further than any other number we publish.
     *
     * <p>So its load-bearing figures are checked against the committed report
     * rather than against themselves.
     */
    @Test
    void theOnePagerAgreesWithTheCommittedReport() throws IOException {
        String page = read(Path.of("..", "docs", "onepager.html"));
        JsonNode r = new ObjectMapper().readTree(
                Files.readString(Path.of("..", "docs", "report_holdout.json"),
                        StandardCharsets.UTF_8));

        JsonNode median = r.path("sweep").path("median");
        JsonNode iqr = r.path("sweep").path("iqr");
        JsonNode budget = r.path("equalBudget");
        JsonNode ablation = r.path("ablations");

        record Figure(String rendered, String source) {
        }
        List<Figure> figures = List.of(
                new Figure(pct1(median.path("baseline")), "sweep median baseline"),
                new Figure(pct1(median.path("capstan")), "sweep median capstan"),
                new Figure(pct1(median.path("upperBound")), "sweep median ceiling"),
                new Figure(pp(iqr.path("baseline")), "sweep IQR baseline"),
                new Figure(pp(iqr.path("capstan")), "sweep IQR capstan"),
                new Figure(pct2(budget.path("3").path("arms").path("baseline")
                        .path("recoveryRatePaise")), "equal budget 3, baseline"),
                new Figure(pct2(budget.path("3").path("arms").path("capstan")
                        .path("recoveryRatePaise")), "equal budget 3, capstan"),
                new Figure(pct2(ablation.path("runs").path("payday-window timing")
                        .path("recoveryRatePaise")), "payday ablation — the inversion line"),
                new Figure(ablation.path("attributionPp").path("over-cap substitution")
                        .asDouble() * -1 + "pp", "over-cap substitution, the 4.36pp we forgo"));

        for (Figure f : figures) {
            assertThat(page)
                    .as("docs/onepager.html no longer carries '%s' (%s) — the one-pager "
                            + "is what gets forwarded, so it must not outlive the report "
                            + "it quotes", f.rendered(), f.source())
                    .contains(f.rendered());
        }
    }

    private static String pct1(JsonNode rate) {
        return String.format("%.1f%%", rate.asDouble() * 100);
    }

    private static String pct2(JsonNode rate) {
        return String.format("%.2f%%", rate.asDouble() * 100);
    }

    private static String pp(JsonNode rate) {
        return String.format("%.2fpp", rate.asDouble() * 100);
    }
    private static String read(Path path) throws IOException {
        assertThat(Files.exists(path))
                .as("%s not found from %s — the scan would pass vacuously",
                        path, Path.of("").toAbsolutePath())
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

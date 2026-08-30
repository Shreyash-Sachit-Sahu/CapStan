package dev.capstan;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static String read(Path path) throws IOException {
        assertThat(Files.exists(path))
                .as("%s not found from %s — the scan would pass vacuously",
                        path, Path.of("").toAbsolutePath())
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

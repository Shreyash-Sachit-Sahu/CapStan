package dev.capstan.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The invariant is enforced by a precondition on one code path, so the number of
 * code paths is itself load-bearing.
 *
 * <p>The ladders cannot enforce it: only NETWORK_TIMEOUT has a
 * {@code RECONCILE_ONLY} rung, and five other ladders step straight from one
 * debit to the next. A second call site to {@code PaymentGateway.debit} would
 * therefore be an unguarded debit-to-debit transition, and it should fail the
 * build rather than fail on stage.
 *
 * <p>A source-text scan, for the same reason {@code OracleIsolationTest} is one:
 * the rule has to be visible to a reader, and the thing being counted is call
 * sites in source, not edges in a call graph.
 */
class DebitGateTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    /** Where the gateway implementations legitimately define and implement debit. */
    private static final String GATEWAY_PACKAGE = "dev/capstan/gateway/";
    private static final String CALL = ".debit(";
    private static final String GATE = "requirePredecessorReconciled";

    @Test
    void exactlyOneCallSiteDebits() throws IOException {
        assertThat(Files.isDirectory(SOURCE_ROOT))
                .as("source root %s not found; the scan would pass vacuously", SOURCE_ROOT)
                .isTrue();

        List<String> callSites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !normalise(path).contains(GATEWAY_PACKAGE))
                    .forEach(path -> {
                        for (String line : lines(path)) {
                            if (line.contains(CALL)) {
                                callSites.add(normalise(path) + "  " + line.strip());
                            }
                        }
                    });
        }

        // Two call sites, and both are named on purpose.
        //
        // DebitWorker is Capstan's, and it is gated. FixedLadderBaseline is the
        // backtest's comparator, and it is deliberately *not* gated: "no
        // reconciliation, a timeout is treated as a failure and retried" is its
        // defining property and the mechanism the ablation measures. Exempting it
        // widens the rule by exactly one file rather than weakening it — a third
        // call site anywhere still fails the build.
        assertThat(callSites)
                .as("every debit must route through DebitWorker (gated) or "
                        + "FixedLadderBaseline (the deliberately un-gated comparator)")
                .hasSize(2);
        assertThat(callSites)
                .anyMatch(site -> site.contains("DebitWorker.java"))
                .anyMatch(site -> site.contains("FixedLadderBaseline.java"));
    }

    @Test
    void theBaselineStaysUnGated() {
        // If someone "fixes" the baseline by giving it the gate, it stops being a
        // baseline and the measured lift silently shrinks toward zero for a reason
        // that has nothing to do with Capstan.
        List<String> baseline = lines(SOURCE_ROOT.resolve(Path.of(
                "dev", "capstan", "backtest", "FixedLadderBaseline.java")));

        assertThat(indexOfLineContaining(baseline, GATE))
                .as("the baseline must not reconcile; that absence is what it measures")
                .isNegative();
    }

    @Test
    void theGateIsCheckedBeforeTheCall() {
        List<String> body = lines(SOURCE_ROOT.resolve(Path.of(
                "dev", "capstan", "execute", "DebitWorker.java")));

        int gate = indexOfLineContaining(body, GATE);
        int call = indexOfLineContaining(body, CALL);

        assertThat(gate).as("DebitWorker must call %s", GATE).isNotNegative();
        assertThat(call).as("DebitWorker must call the gateway").isNotNegative();
        assertThat(gate).as("the precondition must precede the debit").isLessThan(call);
    }

    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalise(Path path) {
        return path.toString().replace('\\', '/');
    }
}

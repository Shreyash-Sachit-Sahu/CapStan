package dev.capstan;

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
 * Phase 07 claims that neither the fixed-ladder baseline nor Capstan itself can
 * read the oracle, and that the comparison between them is therefore fair. That
 * claim is only worth making if something enforces it. This is that something.
 *
 * <p>Deliberately a source-text scan rather than an ArchUnit bytecode rule. The
 * most likely way the isolation gets broken is someone writing raw SQL that
 * names the table in a repository, and a type-level rule cannot see a string
 * literal.
 *
 * <p>Phase 05's SimulatedGateway is allowed to know the answer -- it stands in
 * for reality, and reality knows -- but it must reach it through a port owned by
 * dev.capstan.backtest, never by naming the oracle itself.
 */
class OracleIsolationTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    private static final String ALLOWED_PACKAGE = "dev/capstan/backtest/";
    private static final List<String> FORBIDDEN = List.of("CaseOracle", "case_oracle");

    @Test
    void oracleIsReachableOnlyFromTheBacktestPackage() throws IOException {
        assertThat(Files.isDirectory(SOURCE_ROOT))
                .as("source root %s not found (working dir %s) - the scan would pass vacuously",
                        SOURCE_ROOT, Path.of("").toAbsolutePath())
                .isTrue();

        List<Path> scanned = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !normalise(path).contains(ALLOWED_PACKAGE))
                    .forEach(path -> {
                        scanned.add(path);
                        collectViolations(path, violations);
                    });
        }

        assertThat(scanned)
                .as("scanned no source files - the rule is not actually running")
                .isNotEmpty();

        assertThat(violations)
                .as("the oracle must not be named outside %s", ALLOWED_PACKAGE)
                .isEmpty();
    }

    private static String normalise(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void collectViolations(Path file, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (FORBIDDEN.stream().anyMatch(line::contains)) {
                    violations.add(normalise(file) + ":" + (i + 1) + "  " + line.strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

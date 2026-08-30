package dev.capstan.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The backtest advances 35 days in seconds. Anything that reads real time inside
 * it produces timestamps from a different universe than the one being simulated —
 * and the failure is silent: injected timeout traps resolve at the wrong virtual
 * instant, or never, and the report looks entirely plausible.
 *
 * <p>Two surfaces, because the rule was only ever enforced on one of them. Java
 * was clean. SQL was not: three columns carried {@code DEFAULT now()}, and
 * {@code payment_attempt.initiated_at} is what the reconcile budget measures
 * against. It happened to be written explicitly on every path, so the bug was
 * latent rather than live — which is the kind that survives review.
 */
class NoWallClockInBacktestTest {

    private static final Path JAVA_ROOT = Path.of("src", "main", "java");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /** Wall-clock reads. {@code Instant.now(clock)} is the injected clock and is fine. */
    private static final Pattern WALL_CLOCK = Pattern.compile(
            "Instant\\.now\\(\\)|LocalDate\\.now\\(\\)|LocalDateTime\\.now\\(\\)"
                    + "|ZonedDateTime\\.now\\(\\)|OffsetDateTime\\.now\\(\\)|new Date\\(\\)");

    /** The rate-limit throttle is a real API constraint, not simulated time. */
    private static final String THROTTLE = "GeminiClassifier.java";

    @Test
    void noJavaPathReadsWallTime() throws IOException {
        assertThat(Files.isDirectory(JAVA_ROOT))
                .as("source root not found; the scan would pass vacuously").isTrue();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(THROTTLE))
                    .forEach(p -> {
                        List<String> lines = read(p);
                        for (int i = 0; i < lines.size(); i++) {
                            if (WALL_CLOCK.matcher(lines.get(i)).find()) {
                                violations.add(p + ":" + (i + 1) + "  " + lines.get(i).strip());
                            }
                        }
                    });
        }
        assertThat(violations)
                .as("every instant must come from the injected Clock or a parameter")
                .isEmpty();
    }

    @Test
    void noColumnAssignsItsOwnTimestamp() throws IOException {
        List<String> defaults = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MIGRATIONS)) {
            files.filter(p -> p.toString().endsWith(".sql")).sorted().forEach(p -> {
                for (String line : read(p)) {
                    // Skip comments: the migrations that removed these defaults
                    // explain why, and the explanation contains the phrase.
                    if (line.strip().startsWith("--")) {
                        continue;
                    }
                    if (line.toLowerCase().contains("default now()")) {
                        defaults.add(p.getFileName() + "  " + line.strip());
                    }
                }
            });
        }
        // V2 declared three; V7 drops all three. The declarations stay in V2
        // because Flyway checksums applied migrations -- so this counts what the
        // schema actually has, by asking the migrations that removed them.
        long dropped = 0;
        try (Stream<Path> files = Files.walk(MIGRATIONS)) {
            dropped = files.filter(p -> p.toString().endsWith(".sql"))
                    .flatMap(p -> read(p).stream())
                    .filter(l -> !l.strip().startsWith("--"))
                    .filter(l -> l.toLowerCase().contains("drop default"))
                    .count();
        }
        assertThat(dropped)
                .as("every DEFAULT now() declared in an earlier migration must be dropped")
                .isEqualTo(defaults.size());
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

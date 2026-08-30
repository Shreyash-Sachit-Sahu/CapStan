package dev.capstan.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.capstan.execute.Fixture;
import dev.capstan.gateway.SimulatedGateway;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * A backtest that does not replay identically cannot support a claim, because
 * any difference between two reports could be the change you made or could be
 * the weather. Both arms are deterministic by construction — the simulator is
 * seeded, and every gateway roll derives from the idempotency key rather than a
 * random source — and this is what holds that property in place.
 *
 * <p>It is also what makes the ablations meaningful: if the reference run drifted
 * between measurements, the attribution would be measuring noise.
 */
@SpringBootTest
@ActiveProfiles("test")
class BacktestDeterminismTest {

    private static final String BATCH = Fixture.LABEL;

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private BacktestRunner runner;
    @Autowired
    private SimulatedGateway gateway;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture(jdbc);
        Fixture.clean(jdbc);
        gateway.reset();

        Instant t0 = Instant.parse("2026-08-05T10:00:00Z");  // cycle spans a 1st, so PAYDAY_RETRY can fire
        for (int i = 0; i < 6; i++) {
            fixture.recoverableCase(t0.plusSeconds(i * 3600L), 100_000L + i * 25_000L);
        }
        // At least one case that walks the re-auth path. Without it the fixture
        // never writes reauth_requested_at, so a second run cannot diverge from
        // the first and this test passes while the bug it exists to catch is live.
        fixture.reauthCase(t0.plusSeconds(7 * 3600L), 180_000L);
    }

    @AfterEach
    void tearDown() {
        Fixture.clean(jdbc);
        gateway.reset();
    }

    @Test
    void twoRunsOfTheSameBatchProduceIdenticalMetrics() {
        List<String> arms = List.of("baseline", "capstan", "upperBound");

        BacktestRunner.Report first = runner.run(BATCH, arms, Ablation.NONE, "timeout_rate:0.10");
        BacktestRunner.Report second = runner.run(BATCH, arms, Ablation.NONE, "timeout_rate:0.10");

        for (String arm : arms) {
            assertThat(second.arms().get(arm))
                    .as("arm %s must replay identically", arm)
                    .isEqualTo(first.arms().get(arm));
        }
        assertThat(second.atRiskPaise()).isEqualTo(first.atRiskPaise());
    }

    @Test
    void theRunRefusesAnUndiagnosedBatch() {
        jdbc.sql("update recovery_case set diagnosed_cause = null where batch_label = :label")
                .param("label", BATCH).update();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> runner.run(BATCH, List.of("capstan"), Ablation.NONE, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undiagnosed")
                .hasMessageContaining("prepare");
    }

    @Test
    void everyArmIsScoredOffTheSameGateway() {
        // Fairness is structural rather than argued: the metrics come from
        // gateway movements, so an arm cannot flatter itself by counting its own
        // work differently.
        BacktestRunner.Report report =
                runner.run(BATCH, List.of("baseline", "capstan"), Ablation.NONE, null);

        int cases = report.cases();
        assertThat(report.arms().get("baseline").cases()).isEqualTo(cases);
        assertThat(report.arms().get("capstan").cases()).isEqualTo(cases);
        assertThat(report.arms().get("baseline").atRiskPaise())
                .as("both arms are measured against the same money at risk")
                .isEqualTo(report.arms().get("capstan").atRiskPaise());
    }

    @Test
    void anAblationChangesSomethingAndNothingElse() {
        BacktestRunner.Report full = runner.run(BATCH, List.of("capstan"), Ablation.NONE, null);
        BacktestRunner.Report without =
                runner.run(BATCH, List.of("capstan"), Ablation.RAIL_SWITCH, null);

        assertThat(without.arms().get("capstan").cases())
                .isEqualTo(full.arms().get("capstan").cases());
        assertThat(without.arms().get("capstan").atRiskPaise())
                .isEqualTo(full.arms().get("capstan").atRiskPaise());
    }

    @Test
    void arbitraryCaseIdsAreNotRequiredToRecover() {
        // Guards the fixture itself: if every case recovered trivially the
        // determinism assertions above would be vacuous.
        BacktestRunner.Report report = runner.run(BATCH, List.of("capstan"), Ablation.NONE, null);
        List<UUID> ids = jdbc.sql("select id from recovery_case where batch_label = :label")
                .param("label", BATCH).query(UUID.class).list();

        assertThat(ids).hasSize(report.cases());
        assertThat(report.arms().get("capstan").debitAttempts())
                .as("the run must actually have attempted something")
                .isPositive();
    }
}

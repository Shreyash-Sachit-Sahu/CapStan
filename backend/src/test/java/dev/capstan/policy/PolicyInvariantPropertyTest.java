package dev.capstan.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.capstan.diagnose.Diagnosis;
import dev.capstan.diagnose.FailureCause;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Drives ten thousand generated case histories through the engine and asserts the
 * stopping rules hold on every one.
 *
 * <p>Unit tests check the guardrails individually. These check the *chain* — the
 * ordering bugs that only appear when a substitution feeds into an advance which
 * feeds into a cap, at a time of day that happens to be quiet hours. Nothing here
 * touches a database or a Spring context: the engine is pure, so a full lifecycle
 * is a loop.
 */
class PolicyInvariantPropertyTest {

    private static final int HISTORIES = 10_000;
    private static final int MAX_STEPS_PER_CASE = 16;

    private final PolicyTable table = new PolicyTable();
    private final PolicyEngine engine = new PolicyEngine(table);

    private static final List<FailureCause> CAUSES = List.of(FailureCause.values());
    private static final List<Diagnosis.DiagnosisMethod> METHODS =
            List.of(Diagnosis.DiagnosisMethod.values());

    @Test
    void stoppingRulesHoldOverTenThousandHistories() {
        int terminated = 0;
        int debitsSeen = 0;

        for (int seed = 0; seed < HISTORIES; seed++) {
            Random rng = new Random(seed);
            Lifecycle result = run(rng, seed);

            // (1) the debit cap is never exceeded
            assertThat(result.debits)
                    .as("seed %s: %s debits against a cap of %s (%s)",
                            seed, result.debits, result.maxDebits, result.policyName)
                    .isLessThanOrEqualTo(result.maxDebits);

            // (2) no case is left non-terminal past the cycle end
            assertThat(result.terminal)
                    .as("seed %s: still non-terminal after %s steps (last action %s)",
                            seed, MAX_STEPS_PER_CASE, result.lastAction)
                    .isTrue();

            // (3) every terminal case carries a reason -- otherwise it is invisible
            // in the Phase 07 exception list, which is the output the bar names
            assertThat(result.terminalReason)
                    .as("seed %s: terminated as %s with no reason", seed, result.terminalStatus)
                    .isNotBlank();

            terminated++;
            debitsSeen += result.debits;
        }

        assertThat(terminated).isEqualTo(HISTORIES);
        // Guards the guard: if the generator only ever produced dead-end cases the
        // debit cap would pass vacuously.
        assertThat(debitsSeen).as("no history ever authorised a debit").isPositive();
    }

    @Test
    void aCasePastItsCycleEndIsAlwaysTerminalWhateverItsState() {
        Random rng = new Random(7);
        for (int i = 0; i < 500; i++) {
            DecisionContext ctx = randomContext(rng, i);
            Instant afterCycle = ctx.billingCycleEnd().plusSeconds(1 + rng.nextInt(86_400));
            DecisionRecord record = engine.decide(reseat(ctx, ctx.ladderPosition(),
                    ctx.debitAttemptsMade(), afterCycle));

            assertThat(record.isTerminal())
                    .as("case %s past cycle end produced %s", i, record.finalAction())
                    .isTrue();
            assertThat(record.finalAction().isDebit).isFalse();
        }
    }

    @Test
    void everyDecisionIsReproducible() {
        Random rng = new Random(99);
        for (int i = 0; i < 500; i++) {
            DecisionContext ctx = randomContext(rng, i);
            assertThat(engine.decide(ctx))
                    .as("case %s was not reproducible", i)
                    .isEqualTo(engine.decide(ctx));
        }
    }

    private record Lifecycle(boolean terminal, String terminalStatus, String terminalReason,
                             int debits, int maxDebits, String policyName,
                             InterventionKind lastAction) {
    }

    private Lifecycle run(Random rng, int seed) {
        DecisionContext ctx = randomContext(rng, seed);
        int position = 0;
        int debits = 0;
        Instant now = ctx.now();
        int maxDebits = 0;
        String policyName = null;
        InterventionKind lastAction = null;

        for (int step = 0; step < MAX_STEPS_PER_CASE; step++) {
            DecisionRecord record = engine.decide(reseat(ctx, position, debits, now));
            policyName = record.policyName();
            maxDebits = maxDebitsFrom(record);
            lastAction = record.finalAction();

            if (record.isTerminal()) {
                return new Lifecycle(true, record.terminalStatus(), record.terminalReason(),
                        debits, maxDebits, policyName, lastAction);
            }

            if (record.finalAction().isDebit) {
                debits++;
            }
            position = record.ladderPosition() + 1;
            // Advance to when the action was scheduled; the executor would too.
            now = record.scheduledFor() == null ? now.plusSeconds(3600)
                    : record.scheduledFor().plusSeconds(60);
        }
        return new Lifecycle(false, null, null, debits, maxDebits, policyName, lastAction);
    }

    /** Reads the cap off the published record rather than reaching into the table. */
    private static int maxDebitsFrom(DecisionRecord record) {
        return record.stopConditions().stream()
                .filter(s -> s.startsWith("max_debit_attempts="))
                .map(s -> Integer.parseInt(s.substring("max_debit_attempts=".length())))
                .findFirst()
                .orElseGet(() -> fail("record carried no max_debit_attempts stop condition"));
    }

    private static DecisionContext reseat(DecisionContext ctx, int position, int debits, Instant now) {
        return new DecisionContext(ctx.caseId(), now, ctx.cause(), ctx.diagnosisConfidence(),
                ctx.diagnosisMethod(), ctx.diagnosisEvidence(), ctx.amountPaise(),
                ctx.firstFailedAt(), ctx.billingCycleEnd(), ctx.mandateStatus(),
                ctx.mandateValidFrom(), ctx.mandateValidUntil(), ctx.mandateMaxAmountPaise(),
                ctx.rail(), ctx.alternateRail(), ctx.contactOptedOut(), ctx.riskFlagged(),
                ctx.preferredLocale(), position, debits, ctx.commsSentInCycle(),
                debits > 0 ? now.minusSeconds(5 * 3600) : null, ctx.lastCommsAt(),
                ctx.inferredSalaryDay(), ctx.recentAttemptsSameReason(),
                ctx.recentFailuresSameReason(), ctx.failureReason(),
                ctx.reauthRequestedAt(), ctx.reauthCompletedAt(), ctx.disabledGuardrails());
    }

    private static DecisionContext randomContext(Random rng, int seed) {
        Instant firstFailed = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(rng.nextInt(172_800));
        Instant cycleEnd = firstFailed.plusSeconds(35L * 86_400);

        boolean mandateKnown = rng.nextInt(10) > 0;
        boolean capKnown = rng.nextInt(10) > 0;
        boolean riskKnown = rng.nextInt(10) > 0;

        long amount = 4_900 + rng.nextInt(1_495_000);
        Long cap = capKnown ? (long) (amount * (rng.nextBoolean() ? 0.5 : 2.0)) : null;

        return new DecisionContext(
                new UUID(rng.nextLong(), rng.nextLong()),
                firstFailed.plusSeconds(rng.nextInt(3600)),
                CAUSES.get(rng.nextInt(CAUSES.size())),
                rng.nextDouble(),
                METHODS.get(rng.nextInt(METHODS.size())),
                "generated for seed " + seed,
                amount,
                firstFailed,
                cycleEnd,
                mandateKnown ? pick(rng, "ACTIVE", "ACTIVE", "REVOKED", "EXPIRED") : null,
                mandateKnown ? firstFailed.minusSeconds(rng.nextInt(30_000_000)) : null,
                mandateKnown ? firstFailed.plusSeconds(rng.nextInt(30_000_000) - 10_000_000) : null,
                cap,
                pick(rng, "UPI_AUTOPAY", "CARD", "ENACH"),
                rng.nextBoolean() ? pick(rng, "CARD", "ENACH") : null,
                rng.nextInt(10) == 0,
                riskKnown ? rng.nextInt(12) == 0 : null,
                rng.nextBoolean() ? "en-IN" : "hi-IN",
                0, 0, rng.nextInt(4),
                null,
                rng.nextBoolean() ? firstFailed.minusSeconds(rng.nextInt(200_000)) : null,
                rng.nextBoolean() ? 1 + rng.nextInt(28) : null,
                rng.nextInt(30),
                rng.nextInt(30),
                pick(rng, "insufficient_funds", "payment_failed", "bank_technical_error"),
                null, null,
                java.util.Set.of());
    }

    private static String pick(Random rng, String... options) {
        return options[rng.nextInt(options.length)];
    }
}

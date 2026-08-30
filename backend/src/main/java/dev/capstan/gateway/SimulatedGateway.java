package dev.capstan.gateway;

import dev.capstan.backtest.OracleOutcomeResolver;
import dev.capstan.backtest.OracleOutcomeResolver.Truth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stands in for reality during the backtest, and reality knows the answer.
 *
 * <p><b>It does not honour idempotency keys.</b> That is deliberate, and it is
 * what makes {@code NoDoubleChargeUnderTimeoutTest} worth running: calling
 * {@link #debit} twice with the same key moves money twice, exactly as
 * Razorpay's payment creation would. The only things standing between a lost
 * response and a double charge are {@code uq_idem} and the reconciliation gate
 * in {@code DebitWorker}. If the simulated gateway silently deduplicated, the
 * test would pass without either of them working.
 *
 * <p>Deterministic throughout: every roll derives from the idempotency key
 * rather than a random source, so a backtest replays identically.
 */
@Component
@Profile("!razorpay")
@RequiredArgsConstructor
public class SimulatedGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedGateway.class);

    private final OracleOutcomeResolver oracle;

    /** Every debit where money actually moved. Not keyed -- appended per call. */
    private final Map<UUID, List<Movement>> moved = new ConcurrentHashMap<>();
    private final Map<UUID, List<CommsCommand>> comms = new ConcurrentHashMap<>();
    private final AtomicInteger debitCalls = new AtomicInteger();

    private volatile Faults faults = Faults.none();

    /**
     * @param doomed        the oracle says this attempt could not have succeeded at
     *                      this instant for a structural reason — the case is not
     *                      recoverable, the money had not arrived, or the rail was
     *                      wrong. Distinguished from losing the probability roll,
     *                      because only the former is a <i>wasted</i> attempt: a
     *                      well-timed retry that happened to fail is not a mistake.
     * @param riskBlocked   the oracle's true cause is RISK_BLOCKED. Debiting one of
     *                      these is a safety failure, not a recovery failure.
     */
    public record Movement(String idempotencyKey, String gatewayRef, Outcome outcome, Instant at,
                           boolean doomed, boolean riskBlocked, long amountPaise) {
    }

    /**
     * @param timeoutAfterDebit how many debits move money and then lose the
     *                          response -- the dangerous fault
     * @param issuerDownWindow  how long the issuer refuses outright after the
     *                          first debit -- a clean failure, no money moved
     * @param reconcileBlind    the lookup API is down too, so an unknown stays
     *                          unknown and the reconcile budget actually runs out
     * @param maxDebitsPerCase  a hard ceiling both arms hit identically, used to
     *                          answer whether the gap between them comes from
     *                          choosing retries better or simply from having more
     *                          of them. 0 means no ceiling.
     */
    public record Faults(int timeoutAfterDebit, Duration issuerDownWindow, boolean reconcileBlind,
                        double timeoutRate, int maxDebitsPerCase) {

        public static Faults none() {
            return new Faults(0, Duration.ZERO, false, 0.0, 0);
        }

        /** Parses {@code timeout_after_debit:6,issuer_down:45m,reconcile_blind:true}. */
        public static Faults parse(String spec) {
            int timeouts = 0;
            Duration down = Duration.ZERO;
            boolean blind = false;
            double rate = 0.0;
            int budget = 0;
            if (spec != null && !spec.isBlank()) {
                for (String part : spec.split(",")) {
                    String[] kv = part.trim().split("[:=]", 2);
                    if (kv.length != 2) {
                        throw new IllegalArgumentException("bad inject clause: " + part);
                    }
                    switch (kv[0].trim()) {
                        case "timeout_after_debit" -> timeouts = Integer.parseInt(kv[1].trim());
                        case "issuer_down", "issuer_down_window" ->
                                down = Duration.ofMinutes(Long.parseLong(kv[1].trim().replace("m", "")));
                        case "reconcile_blind" -> blind = Boolean.parseBoolean(kv[1].trim());
                        case "timeout_rate" -> rate = Double.parseDouble(kv[1].trim());
                        case "max_debits_per_case" -> budget = Integer.parseInt(kv[1].trim());
                        default -> throw new IllegalArgumentException("unknown fault: " + kv[0]);
                    }
                }
            }
            return new Faults(timeouts, down, blind, rate, budget);
        }
    }

    public void inject(Faults injected) {
        this.faults = injected;
        log.warn("SimulatedGateway faults set: {}", injected);
    }

    public void reset() {
        moved.clear();
        comms.clear();
        debitCalls.set(0);
        faults = Faults.none();
    }

    /** One entry per debit where money moved. The double-charge assertion. */
    public List<Movement> movementsFor(UUID caseId) {
        return List.copyOf(moved.getOrDefault(caseId, List.of()));
    }

    public int debitCallCount() {
        return debitCalls.get();
    }

    public List<CommsCommand> commsFor(UUID caseId) {
        return List.copyOf(comms.getOrDefault(caseId, List.of()));
    }

    @Override
    public AttemptResult debit(DebitCommand cmd) {
        int call = debitCalls.incrementAndGet();
        List<Movement> history = moved.computeIfAbsent(cmd.caseId(), k -> new CopyOnWriteArrayList<>());

        // Equal-budget mode: the ceiling is enforced here so both arms meet the
        // identical wall regardless of how their ladders are shaped. Nothing is
        // recorded, because no money moved.
        if (faults.maxDebitsPerCase() > 0 && history.size() >= faults.maxDebitsPerCase()) {
            return AttemptResult.failed(null, "attempt_budget_exhausted");
        }

        if (issuerDown(cmd, history)) {
            // Refused at the door. Nothing moved, so this is a clean FAILED and
            // the case may legitimately retry once the window passes.
            return AttemptResult.failed(null, "issuer_unreachable");
        }

        Assessment assessment = assess(cmd);
        Outcome truth = assessment.succeeds() ? Outcome.SUCCEEDED : Outcome.FAILED;
        String ref = "sim_" + cmd.idempotencyKey().substring(4, 20);
        history.add(new Movement(cmd.idempotencyKey(), ref, truth, cmd.at(),
                assessment.doomed(), assessment.riskBlocked(), cmd.amountPaise()));

        // timeout_rate is derived from the key, so every arm trips on the same
        // (case, attempt, rail, amount) tuples. Call-order injection would give two
        // arms timeouts on different cases and quietly unbalance the comparison.
        boolean timedOut = call <= faults.timeoutAfterDebit()
                || (faults.timeoutRate() > 0 && roll(cmd.idempotencyKey() + ":timeout") < faults.timeoutRate());
        if (timedOut) {
            // Money has already moved. The caller will never learn what happened
            // from this call; only reconciliation can tell it.
            log.warn("Injected timeout after debit {} for case {}", call, cmd.caseId());
            return AttemptResult.unknown("gateway_timeout");
        }
        return truth == Outcome.SUCCEEDED
                ? AttemptResult.succeeded(ref)
                : AttemptResult.failed(ref, "simulated_decline");
    }

    @Override
    public AttemptResult fetchByIdempotencyKey(String key) {
        if (faults.reconcileBlind()) {
            return AttemptResult.unknown("lookup_unavailable");
        }
        return moved.values().stream()
                .flatMap(List::stream)
                .filter(m -> m.idempotencyKey().equals(key))
                .findFirst()
                .map(m -> m.outcome() == Outcome.SUCCEEDED
                        ? AttemptResult.succeeded(m.gatewayRef())
                        : AttemptResult.failed(m.gatewayRef(), "simulated_decline"))
                .orElseGet(AttemptResult::absent);
    }

    @Override
    public void sendCommunication(CommsCommand cmd) {
        comms.computeIfAbsent(cmd.caseId(), k -> new CopyOnWriteArrayList<>()).add(cmd);
    }

    private boolean issuerDown(DebitCommand cmd, List<Movement> history) {
        if (faults.issuerDownWindow().isZero() || history.isEmpty()) {
            return false;
        }
        Instant from = history.get(0).at();
        return cmd.at().isBefore(from.plus(faults.issuerDownWindow()));
    }

    private record Assessment(boolean succeeds, boolean doomed, boolean riskBlocked) {
    }

    /**
     * Reality's answer. Four gates, all of which must clear: the case has to be
     * recoverable at all, the money has to have arrived, the rail has to be the
     * one the situation needs, and the coin has to land.
     *
     * <p>The first three are <i>structural</i> — no timing or rail choice could
     * have helped at that instant — and failing any of them makes the attempt
     * wasted. The fourth is chance, and losing it is not a mistake. The backtest
     * needs that distinction to report a false-positive cost that means anything.
     */
    private Assessment assess(DebitCommand cmd) {
        Optional<Truth> found = oracle.truthFor(cmd.caseId());
        if (found.isEmpty()) {
            return new Assessment(false, true, false);
        }
        Truth truth = found.get();
        boolean riskBlocked = "RISK_BLOCKED".equals(truth.trueCause());

        if (!truth.recoverable()
                || (truth.recoveryWindowStart() != null
                    && cmd.at().isBefore(truth.recoveryWindowStart()))
                || !railPermits(truth.requiredChannel(), cmd)) {
            return new Assessment(false, true, riskBlocked);
        }

        double probability = "REAUTH_REQUIRED".equals(truth.requiredChannel())
                // A re-auth link only helps if the customer acted on it, which
                // is what nudge_sensitivity measures.
                ? truth.nudgeSensitivity()
                : truth.attemptSuccessProb();
        return new Assessment(roll(cmd.idempotencyKey()) < probability, false, riskBlocked);
    }

    private boolean railPermits(String requiredChannel, DebitCommand cmd) {
        return switch (requiredChannel) {
            case "ANY" -> true;
            case "SAME_RAIL" -> !cmd.alternateRail();
            case "ALTERNATE_RAIL" -> cmd.alternateRail();
            // A debit alone never fixes a dead mandate. Only a re-authorisation
            // the customer completed does -- and specifically a REAUTH_LINK, not
            // any message that happens to have been sent.
            //
            // This previously accepted *any* comms, which meant the baseline's
            // generic "your payment did not go through" SMS re-authorised the
            // mandate. That is false about the world: a dunning notice tells
            // someone a payment failed; re-authorising an e-mandate is the
            // customer completing an authenticated flow with their bank. The
            // error was worth 25.0% of at-risk value and it favoured the
            // baseline. See docs/decisions/2026-08-30-reauth-semantics.md, which
            // was written and committed before the corrected run.
            //
            // Whether the customer actually acted is the probability roll below,
            // which uses nudge_sensitivity for this channel.
            case "REAUTH_REQUIRED" -> commsFor(cmd.caseId()).stream()
                    .anyMatch(sent -> "REAUTH_LINK".equals(sent.kind()));
            default -> throw new IllegalStateException("unknown channel: " + requiredChannel);
        };
    }

    /** Derived from the key, so the same attempt always rolls the same number. */
    private static double roll(String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 6; i++) {
                bits = (bits << 8) | (digest[i] & 0xFFL);
            }
            return (double) bits / (double) (1L << 48);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package dev.capstan.backtest;

import dev.capstan.gateway.IdempotencyKey;
import dev.capstan.gateway.PaymentGateway.AttemptResult;
import dev.capstan.gateway.PaymentGateway.CommsCommand;
import dev.capstan.gateway.PaymentGateway.DebitCommand;
import dev.capstan.gateway.SimulatedGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What an ordinary merchant does today: retry at T+1d, T+3d, T+5d on the same
 * rail regardless of cause, one generic SMS after the second failure, stop at
 * the billing cycle end, and no reconciliation — a timeout counts as a failure
 * and gets retried.
 *
 * <p><b>Not handicapped, and in one respect advantaged.</b> It runs against the
 * same oracle and the same gateway, with no artificial delays and no worse copy.
 * It also has no quiet-hours restriction, so it can message a customer at 03:00
 * where Capstan's G7 cannot — which is faithful to what merchants actually do,
 * and means some of Capstan's recovery is given up to its own guardrail. That
 * cost is reported rather than hidden.
 *
 * <p>Written in memory rather than through Capstan's execution path on purpose:
 * routing it through {@code DebitWorker} would hand it the reconciliation gate,
 * which is precisely the mechanism it is supposed to lack.
 */
@Service
@RequiredArgsConstructor
public class FixedLadderBaseline implements BacktestArm {

    private static final List<Duration> LADDER =
            List.of(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(5));
    /** One generic SMS, after the second failure. */
    private static final int SMS_AFTER_FAILURES = 2;

    private final JdbcClient jdbc;

    private record CaseState(UUID caseId, long amountPaise, String rail, Instant firstFailedAt,
                             Instant cycleEnd, String locale) {
    }

    private final Map<UUID, Integer> attemptsMade = new HashMap<>();
    private final Map<UUID, Boolean> done = new HashMap<>();
    private final Map<UUID, Boolean> smsSent = new HashMap<>();

    @Override
    public String name() {
        return "baseline";
    }

    public int commsSent() {
        return (int) smsSent.values().stream().filter(Boolean::booleanValue).count();
    }

    @Override
    public void run(RunContext ctx) {
        attemptsMade.clear();
        done.clear();
        smsSent.clear();

        List<CaseState> cases = load(ctx.batchLabel());
        VirtualClock clock = ctx.clock();

        while (clock.hasNext()) {
            Instant now = clock.now();
            for (CaseState c : cases) {
                step(c, now, ctx.gateway());
            }
            clock.advance();
        }
    }

    private void step(CaseState c, Instant now, SimulatedGateway gateway) {
        if (Boolean.TRUE.equals(done.get(c.caseId())) || now.isAfter(c.cycleEnd())) {
            return;
        }
        int made = attemptsMade.getOrDefault(c.caseId(), 0);
        if (made >= LADDER.size()) {
            done.put(c.caseId(), true);
            return;
        }
        Instant dueAt = c.firstFailedAt().plus(LADDER.get(made));
        if (now.isBefore(dueAt)) {
            return;
        }

        // Same key derivation as Capstan, so identical (case, sequence, rail,
        // amount) draws the identical coin. Fairness, not idempotency -- the
        // simulated gateway honours no keys at all.
        String key = IdempotencyKey.idempotencyKey(c.caseId(), made + 1, c.rail(), c.amountPaise());
        AttemptResult result = gateway.debit(new DebitCommand(
                c.caseId(), c.caseId(), key, c.rail(), false, c.amountPaise(), now));

        attemptsMade.put(c.caseId(), made + 1);

        switch (result.outcome()) {
            case SUCCEEDED -> done.put(c.caseId(), true);
            // No reconciliation. An unknown outcome is filed as a failure and
            // retried, which is where the duplicate charges come from.
            case FAILED, UNKNOWN -> {
                if (made + 1 == SMS_AFTER_FAILURES && !Boolean.TRUE.equals(smsSent.get(c.caseId()))) {
                    // No quiet-hours check. See the class note.
                    gateway.sendCommunication(new CommsCommand(
                            c.caseId(), "GENERIC_SMS", c.locale(),
                            "Your payment did not go through. Please ensure funds are available.",
                            now));
                    smsSent.put(c.caseId(), true);
                }
            }
        }
    }

    private List<CaseState> load(String batchLabel) {
        return jdbc.sql("""
                select c.id, c.amount_paise, c.first_failed_at, c.billing_cycle_end,
                       m.rail, cu.preferred_locale
                  from recovery_case c
                  join mandate m on m.id = c.mandate_id
                  join merchant_customer cu on cu.id = c.customer_id
                 where c.batch_label = :label
                 order by c.id
                """)
                .param("label", batchLabel)
                .query((rs, rowNum) -> new CaseState(
                        rs.getObject("id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("rail"),
                        rs.getObject("first_failed_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("billing_cycle_end", java.time.OffsetDateTime.class).toInstant(),
                        rs.getString("preferred_locale")))
                .list();
    }
}

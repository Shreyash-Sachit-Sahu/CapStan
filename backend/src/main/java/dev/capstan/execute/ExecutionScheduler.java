package dev.capstan.execute;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The only place in the execution engine that reads wall time.
 *
 * <p>Everything it calls takes {@code now} as a parameter, so Phase 07's tick
 * loop can drive the identical code paths from a virtual clock. That is why
 * this bean is excluded from the {@code test} and {@code backtest} profiles
 * rather than the work being scheduled inside the services: a wall-clock
 * reconciler running alongside a virtual-clock backtest would resolve attempts
 * at the wrong instant, or never.
 *
 * <p>A separate bean from the work it triggers, because {@code @Scheduled} is
 * proxy-based and calling a scheduled method from inside its own bean is a
 * plain method call.
 */
@Component
@Profile({"!test & !backtest"})
@RequiredArgsConstructor
public class ExecutionScheduler {

    private final Dispatcher dispatcher;
    private final Outbox outbox;
    private final Reconciler reconciler;
    private final Clock clock;

    @Scheduled(fixedDelay = 500)
    public void publishOutbox() {
        outbox.publishBatch();
    }

    @Scheduled(fixedDelay = 2_000)
    public void dispatchDue() {
        dispatcher.dispatchDue(Instant.now(clock));
    }

    @Scheduled(fixedDelay = 5_000)
    public void reconcileDue() {
        reconciler.runDue(Instant.now(clock));
    }
}

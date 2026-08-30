package dev.capstan.backtest;

import java.time.Duration;
import java.time.Instant;

/**
 * The backtest's time cursor. A value object, deliberately not a Spring bean.
 *
 * <p>The brief proposed a {@code @Profile("backtest")} {@code Clock} bean. That
 * cannot work here: the acceptance checks POST to {@code /api/backtest/run} on a
 * <i>running</i> application, so a profile-bound clock would leave the app
 * permanently virtual (breaking live execution) or permanently wall-clock
 * (breaking the backtest). It also fails in the more dangerous direction — a
 * code path nobody remembered to thread would silently read whatever the bean
 * returned and produce plausible, wrong timestamps.
 *
 * <p>So the instant travels as a parameter instead, the way
 * {@code Reconciler.runDue(now)} already does. A path that was not threaded does
 * not compile, which is the failure mode worth having.
 */
public final class VirtualClock {

    /** 15 minutes of virtual time per tick; 35 days is 3,360 ticks. */
    public static final Duration STEP = Duration.ofMinutes(15);

    private final Instant start;
    private final Instant end;
    private final Duration step;
    private Instant now;

    public VirtualClock(Instant start, Instant end, Duration step) {
        this.start = start;
        this.end = end;
        this.step = step;
        this.now = start;
    }

    public VirtualClock(Instant start, Instant end) {
        this(start, end, STEP);
    }

    public Instant now() {
        return now;
    }

    public Instant start() {
        return start;
    }

    public Instant end() {
        return end;
    }

    public boolean hasNext() {
        return !now.isAfter(end);
    }

    public void advance() {
        now = now.plus(step);
    }

    public int totalTicks() {
        return (int) (Duration.between(start, end).toMinutes() / step.toMinutes()) + 1;
    }
}

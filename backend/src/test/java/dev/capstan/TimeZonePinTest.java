package dev.capstan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * Guards the surefire argLine. Test JVMs never run CapstanApplication#main, so
 * without -Duser.timezone=UTC they inherit the host zone and every later
 * quiet-hours / payday-window test silently drifts by the host offset.
 */
class TimeZonePinTest {

    @Test
    void jvmIsPinnedToUtc() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}

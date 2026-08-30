package dev.capstan;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /**
     * Every time-conditional decision in Capstan reads the clock through this bean,
     * never through a no-arg now() call. Phase 07 swaps in a VirtualClock so a
     * 35-day billing cycle replays in seconds; call sites do not change because
     * java.time already accepts a Clock (Instant.now(clock), LocalDate.now(clock)).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

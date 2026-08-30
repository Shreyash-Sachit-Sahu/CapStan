package dev.capstan;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CapstanApplication {

    public static void main(String[] args) {
        // Must run before the Spring context starts.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(CapstanApplication.class, args);
    }

    @PostConstruct
    void assertUtc() {
        if (!"UTC".equals(TimeZone.getDefault().getID())) {
            throw new IllegalStateException(
                "JVM timezone is " + TimeZone.getDefault().getID() + ", expected UTC");
        }
    }
}

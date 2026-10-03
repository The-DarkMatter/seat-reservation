package dev.amogh.seats;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code app.*} block of application.yml. */
@ConfigurationProperties("app")
public record AppProperties(
        String jwtSecret,
        String adminSecret,
        Duration tokenTtl,
        Duration sweeperInterval) {
}

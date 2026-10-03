package dev.amogh.seats.demo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.demo.*} block: the public Kursi demo (featured events, visitors'
 * own rush shows, server-side bots) and the limits that keep it from being abused.
 */
@ConfigurationProperties("app.demo")
public record DemoProperties(
        boolean featured,
        Duration rotateAfter,
        double rotateWhenSold,
        Duration retention,
        int maxLiveDemoShows,
        int maxRushBots,
        int createsPerWindow,
        int rushesPerWindow,
        Duration limitWindow) {
}

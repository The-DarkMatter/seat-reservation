package dev.amogh.seats;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full app on a random port against the shared MySQL container. Metrics export
 * is on (Spring disables it in tests by default) so /metrics can be asserted.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.structured.format.console=")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics
public @interface IntegrationTest {
}

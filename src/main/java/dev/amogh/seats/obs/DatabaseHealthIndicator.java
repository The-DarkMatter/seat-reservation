package dev.amogh.seats.obs;

import java.sql.Connection;
import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Readiness check for MySQL, on its own one-connection pool.
 *
 * Using the main pool would get both failure modes wrong. When MySQL is down,
 * the check would hang for the main pool's 20s connection timeout instead of
 * failing fast. During a burst, a saturated (but healthy) pool would make
 * readiness flap DOWN while the service is serving fine. A dedicated pool
 * with a 1.5s timeout answers only "can we reach MySQL right now?", and
 * fails closed (503) within ~1.5s when the answer is no.
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator, DisposableBean {

    private final HikariDataSource probe;

    public DatabaseHealthIndicator(DataSource dataSource) {
        var main = (HikariDataSource) dataSource;
        var config = new HikariConfig();
        config.setPoolName("seats-health");
        config.setJdbcUrl(main.getJdbcUrl());
        config.setUsername(main.getUsername());
        config.setPassword(main.getPassword());
        var props = new Properties();
        props.putAll(main.getDataSourceProperties());
        props.setProperty("connectTimeout", "1000");
        props.setProperty("socketTimeout", "2000");
        config.setDataSourceProperties(props);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1500);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1); // never block startup on this pool
        this.probe = new HikariDataSource(config);
    }

    @Override
    public Health health() {
        try (Connection c = probe.getConnection(); var stmt = c.createStatement()) {
            stmt.setQueryTimeout(1);
            stmt.execute("SELECT 1");
            return Health.up().withDetail("database", "MySQL").build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }

    @Override
    public void destroy() {
        probe.close();
    }
}

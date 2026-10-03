package dev.amogh.seats;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real MySQL 8.4 for the whole test run (every test class shares the same
 * Spring context, so the container starts once). Concurrency tests are only
 * meaningful against the real InnoDB locking behaviour, never an in-memory DB.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withCommand("--transaction-isolation=READ-COMMITTED", "--max-connections=300");
    }
}

package dev.amogh.seats.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;

@IntegrationTest
class ObservabilityTests {

    @LocalServerPort
    int port;
    @Autowired
    SeatGauges gauges;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void livenessAndReadiness() {
        var live = api.get("/health/live", null);
        assertThat(live.status()).isEqualTo(200);
        assertThat(live.body().get("status").asString()).isEqualTo("UP");

        var ready = api.get("/health/ready", null);
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body().get("components").get("database").get("status").asString()).isEqualTo("UP");
    }

    @Test
    void requestIdIsEchoedOrGenerated() {
        var given = api.post("/auth/token", null, Map.of("user_id", "rid"), Map.of("X-Request-Id", "trace-123"));
        assertThat(given.headers().firstValue("X-Request-Id")).contains("trace-123");

        var generated = api.get("/", null);
        assertThat(generated.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(generated.body().get("service").asString()).isEqualTo("seat-reservation");
    }

    @Test
    void metricsMoveWithTheApiAndGaugesMatchShowState() {
        var show = api.post("/shows", api.adminToken(),
                Map.of("name", "metrics", "seats", List.of("M1", "M2", "M3"), "price_paise", 100)).body();
        String showId = show.get("id").asString();
        double confirmedBefore = metric("reservations_confirmed_total", "");
        double takenBefore = metric("reservations_declined_total", "reason=\"seat_taken\"");
        double replayBefore = metric("reservations_declined_total", "reason=\"idempotent_replay\"");

        String alice = api.userToken("m-alice");
        api.post("/shows/" + showId + "/reserve", alice, Map.of("seats", List.of("M1", "M2"), "idempotency_key", "m"));
        api.post("/shows/" + showId + "/reserve", alice, Map.of("seats", List.of("M1", "M2"), "idempotency_key", "m"));
        api.post("/shows/" + showId + "/reserve", api.userToken("m-bob"), Map.of("seats", List.of("M1")));

        assertThat(metric("reservations_confirmed_total", "") - confirmedBefore).isEqualTo(1);
        assertThat(metric("reservations_declined_total", "reason=\"seat_taken\"") - takenBefore).isEqualTo(1);
        assertThat(metric("reservations_declined_total", "reason=\"idempotent_replay\"") - replayBefore).isEqualTo(1);

        gauges.refresh();
        String showTag = "show_id=\"" + showId + "\"";
        var state = api.get("/shows/" + showId, null).body().get("counts");
        assertThat(metric("seats_available", showTag)).isEqualTo(state.get("available").asInt());
        assertThat(metric("seats_confirmed", showTag)).isEqualTo(state.get("confirmed").asInt()).isEqualTo(2);
        assertThat(metric("seats_held", showTag)).isZero();
        assertThat(metric("seats_capacity", showTag)).isEqualTo(3);
    }

    @Test
    void latencyHistogramsAreExported() {
        String text = api.get("/metrics", null).raw();
        assertThat(text).contains("http_server_requests_seconds_bucket");
        assertThat(text).contains("hikaricp_connections_active");
        assertThat(text).contains("reservation_lock_retries_total");
    }

    /** Value of one sample line in the Prometheus text exposition, summed over matching series. */
    private double metric(String name, String labelFilter) {
        String text = api.get("/metrics", null).raw();
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "(\\{[^}]*})? (\\S+)$", Pattern.MULTILINE);
        Matcher m = line.matcher(text);
        double sum = 0;
        boolean found = false;
        while (m.find()) {
            String labels = m.group(1) == null ? "" : m.group(1);
            if (labels.contains(labelFilter)) {
                sum += Double.parseDouble(m.group(2));
                found = true;
            }
        }
        assertThat(found).as("metric %s{%s} present", name, labelFilter).isTrue();
        return sum;
    }
}

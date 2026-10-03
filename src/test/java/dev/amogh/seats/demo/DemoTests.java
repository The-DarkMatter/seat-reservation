package dev.amogh.seats.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReservationService;
import dev.amogh.seats.show.CreateShowRequest;
import dev.amogh.seats.show.ShowService;
import dev.amogh.seats.web.ApiException;

@IntegrationTest
class DemoTests {

    @Autowired
    DemoService demo;
    @Autowired
    RushService rush;
    @Autowired
    ShowService shows;
    @Autowired
    ReservationService reservations;
    @Autowired
    DemoRepository repository;
    @Autowired
    JdbcClient jdbc;
    @LocalServerPort
    int port;

    @Test
    void everyTemplateHasAFeaturedShow() {
        demo.refreshFeatured();
        var api = new ApiClient(port);

        var listing = api.get("/shows", null).body();

        assertThat(listing.findValuesAsString("name")).contains(
                "Monsoon Raag: Live in Concert", "Kursi Ki Ladai: A Political Satire", "Midnight Chai Comedy Hour");
        var templates = api.get("/demo/templates", null).body();
        assertThat(templates.findValuesAsString("id")).containsExactly("arena", "club", "theatre");
    }

    @Test
    void aMostlySoldFeaturedShowIsSwappedForAFreshOne() {
        demo.refreshFeatured();
        String name = "Midnight Chai Comedy Hour";
        String before = repository.latestFeatured(name).orElseThrow().id();
        var labels = shows.get(before).seats().stream().map(s -> s.seat()).toList(); // 140 seats, limit 4
        for (int i = 0; i * 4 < 112; i++) {
            reservations.reserve("crowd-" + i, before, labels.subList(i * 4, i * 4 + 4), null);
        }

        demo.refreshFeatured();

        String after = repository.latestFeatured(name).orElseThrow().id();
        assertThat(after).isNotEqualTo(before);
        var api = new ApiClient(port);
        assertThat(api.get("/shows", null).body().findValuesAsString("id")).contains(after).doesNotContain(before);
        assertThat(api.get("/shows/" + before, null).status()).isEqualTo(200); // hidden, not gone
    }

    @Test
    void visitorsCanCreateTheirOwnShowButNotTooMany() {
        var api = new ApiClient(port);
        var headers = Map.of("X-Forwarded-For", "203.0.113.77");

        var created = api.post("/demo/shows", null, Map.of("template", "club", "hold_ttl_seconds", 60), headers);

        assertThat(created.status()).isEqualTo(201);
        String id = created.body().get("id").asString();
        var show = api.get("/shows/" + id, null).body();
        assertThat(show.get("hold_ttl_seconds").asInt()).isEqualTo(60);
        assertThat(show.get("layout").get("template").asString()).isEqualTo("club");
        assertThat(api.get("/shows?kind=api&limit=50", null).body().findValuesAsString("id")).doesNotContain(id);

        for (int i = 1; i < 6; i++) {
            assertThat(api.post("/demo/shows", null, Map.of("template", "club"), headers).status()).isEqualTo(201);
        }
        var limited = api.post("/demo/shows", null, Map.of("template", "club"), headers);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body().get("error").asString()).isEqualTo("rate_limited");
        // Someone else (another IP) is unaffected.
        assertThat(api.post("/demo/shows", null, Map.of("template", "club"), Map.of("X-Forwarded-For", "203.0.113.78"))
                .status()).isEqualTo(201);
        assertThat(api.post("/demo/shows", null, Map.of("template", "nope"), Map.of("X-Forwarded-For", "203.0.113.79"))
                .status()).isEqualTo(400);
    }

    @Test
    void aRushSellsEachSeatOnceAndReportsWhatHappened() throws Exception {
        String show = demo.createDemoShow("club", null).id();

        var started = rush.start(show, 400);
        assertThat(started.running()).isTrue();
        assertThatThrownBy(() -> rush.start(show, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));

        var status = waitForRush(show, Duration.ofSeconds(60));

        assertThat(status.errors()).isZero();
        assertThat(status.reservations()).isPositive();
        assertThat(status.declined()).containsKey("seat_taken");
        var view = shows.get(show);
        assertThat(view.counts().held() + view.counts().confirmed()).isEqualTo(status.seats());
        assertThat(view.counts().available() + view.counts().held() + view.counts().confirmed()).isEqualTo(140);
        assertThat(view.counts().confirmed()).isEqualTo(confirmedSeatsOfPaidReservations(show));
    }

    @Test
    void rushesOnlyRunOnDemoShows() {
        demo.refreshFeatured();
        String featured = repository.latestFeatured("Monsoon Raag: Live in Concert").orElseThrow().id();
        String api = shows.create(new CreateShowRequest("api-show", List.of("A1"), 100L, null, null)).id();

        for (String id : List.of(featured, api)) {
            assertThatThrownBy(() -> rush.start(id, 10))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        }
    }

    @Test
    void cleanupDeletesOldDemoShowsAndNothingElse() {
        String oldDemo = demo.createDemoShow("club", null).id();
        String freshDemo = demo.createDemoShow("club", null).id();
        String oldApi = shows.create(new CreateShowRequest("old-api", List.of("A1"), 100L, null, null)).id();
        reservations.reserve("someone", oldDemo, List.of("HOUSE-A1"), "old-key");
        for (String id : List.of(oldDemo, oldApi)) {
            jdbc.sql("UPDATE shows SET created_at = NOW(6) - INTERVAL 2 DAY WHERE id = ?").param(id).update();
        }
        jdbc.sql("UPDATE idempotency_keys SET created_at = NOW(6) - INTERVAL 2 DAY WHERE idem_key = 'old-key'").update();

        demo.cleanup();

        var api = new ApiClient(port);
        assertThat(api.get("/shows/" + oldDemo, null).status()).isEqualTo(404);
        assertThat(api.get("/shows/" + freshDemo, null).status()).isEqualTo(200);
        assertThat(api.get("/shows/" + oldApi, null).status()).isEqualTo(200);
        int keys = jdbc.sql("SELECT COUNT(*) FROM idempotency_keys WHERE idem_key = 'old-key'").query(Integer.class).single();
        assertThat(keys).isZero();
    }

    private int confirmedSeatsOfPaidReservations(String show) {
        return jdbc.sql("SELECT COALESCE(SUM(JSON_LENGTH(seats)), 0) FROM reservations WHERE show_id = ? AND status = 'confirmed'")
                .param(show).query(Integer.class).single();
    }

    private RushService.RushStatus waitForRush(String show, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var status = rush.status(show);
            if (!status.running()) {
                // Paying bots confirm up to 2s after their reserve; the rush waits for them.
                return status;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("rush did not finish in " + timeout);
    }
}

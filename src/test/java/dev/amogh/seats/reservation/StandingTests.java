package dev.amogh.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReserveResult.Declined;
import dev.amogh.seats.reservation.ReserveResult.Replayed;
import dev.amogh.seats.reservation.ReserveResult.Reserved;
import dev.amogh.seats.show.CreateShowRequest;
import dev.amogh.seats.show.CreateShowRequest.RowSpec;
import dev.amogh.seats.show.CreateShowRequest.SectionSpec;
import dev.amogh.seats.show.ShowService;
import dev.amogh.seats.show.ShowView;
import dev.amogh.seats.web.ApiException;

/** Standing sections: booked by quantity, places picked with FOR UPDATE SKIP LOCKED. */
@IntegrationTest
class StandingTests {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;
    @Autowired
    JdbcClient jdbc;
    @LocalServerPort
    int port;

    private String newShow(int standingCapacity, int perUserLimit) {
        return shows.create(new CreateShowRequest("standing", null, null, perUserLimit, null, null, null,
                List.of(new SectionSpec("GOLD", "Gold", 500000L, false, null, List.of(new RowSpec("A", 10, null)), null),
                        new SectionSpec("PIT", "Pit", 200000L, true, standingCapacity, null, null)),
                null)).id();
    }

    private ReserveResult standing(String user, String show, int quantity, String key) {
        return reservations.reserve(user, show, new SeatRequest.Standing("PIT", quantity), key);
    }

    @Test
    void stormSellsEveryPlaceExactlyOnceAndNoMore() throws Exception {
        String show = newShow(100, 4);

        // 500 buyers want 1-3 places each in a pit of 100, all at the same instant.
        var results = race(500, i -> standing("fan-" + i, show, 1 + i % 3, null));

        var sold = new ArrayList<String>();
        int declined = 0;
        for (var r : results) {
            switch (r) {
                case Reserved ok -> sold.addAll(ok.reservation().seats());
                case Declined no -> {
                    assertThat(no.reason()).isEqualTo(DeclineReason.SECTION_SOLD_OUT);
                    declined++;
                }
                case Replayed p -> throw new AssertionError("no keys were sent");
            }
        }
        assertThat(new HashSet<>(sold)).hasSize(sold.size()); // never the same place twice
        assertThat(sold.size()).isLessThanOrEqualTo(100);
        assertThat(declined).isPositive();
        // Once the dust settles, only a request bigger than what's left can fail, so at
        // most 2 places stay unsold (a 3-place ask can fail when 1 or 2 remain).
        assertThat(sold.size()).isGreaterThanOrEqualTo(98);

        var view = shows.get(show);
        var pit = view.sections().get(1);
        assertThat(pit.counts().confirmed()).isEqualTo(sold.size());
        assertThat(pit.counts().available() + pit.counts().held() + pit.counts().confirmed()).isEqualTo(100);
        assertThat(view.sections().getFirst().counts()).isEqualTo(new ShowView.Counts(10, 0, 0));
        int rows = jdbc.sql("SELECT COUNT(*) FROM seats WHERE show_id = ? AND section = 'PIT' AND status = 'confirmed'")
                .param(show).query(Integer.class).single();
        assertThat(rows).isEqualTo(sold.size());
    }

    @Test
    void askingForMoreThanIsLeftTakesNothing() {
        String show = newShow(5, 10);
        assertThat(standing("a", show, 3, null)).isInstanceOf(Reserved.class);

        var result = standing("b", show, 3, null);

        assertThat(result).isInstanceOfSatisfying(Declined.class, d -> {
            assertThat(d.reason()).isEqualTo(DeclineReason.SECTION_SOLD_OUT);
            assertThat(d.body()).contains("\"available\":2").contains("Only 2 places left in Pit");
        });
        assertThat(shows.get(show).sections().get(1).counts().available()).isEqualTo(2);
    }

    @Test
    void retriesWithTheSameKeyGetTheSamePlaces() {
        String show = newShow(50, 4);
        var first = (Reserved) standing("kiran", show, 2, "k-1");

        var again = standing("kiran", show, 2, "k-1");
        var different = standing("kiran", show, 3, "k-1");

        assertThat(again).isInstanceOfSatisfying(Replayed.class, r -> assertThat(r.body()).isEqualTo(first.body()));
        assertThat(different).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.IDEMPOTENCY_KEY_REUSE));
        assertThat(shows.get(show).sections().get(1).counts().confirmed()).isEqualTo(2);
    }

    @Test
    void perUserLimitCountsSeatedAndStandingTogether() {
        String show = newShow(50, 4);
        assertThat(reservations.reserve("meera", show, List.of("GOLD-A1", "GOLD-A2", "GOLD-A3"), null))
                .isInstanceOf(Reserved.class);

        assertThat(standing("meera", show, 2, null)).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.PER_USER_LIMIT));
        assertThat(standing("meera", show, 1, null)).isInstanceOf(Reserved.class);
    }

    @Test
    void rejectsMalformedStandingRequests() {
        String show = newShow(10, 4);
        assertBadRequest(() -> reservations.reserve("x", show, List.of("PIT-001"), null), "standing_section");
        assertBadRequest(() -> reservations.reserve("x", show, new SeatRequest.Standing("GOLD", 1), null),
                "unknown_section");
        assertBadRequest(() -> reservations.reserve("x", show, new SeatRequest.Standing("NOPE", 1), null),
                "unknown_section");
        assertBadRequest(() -> standing("x", show, 0, null), "invalid_quantity");
    }

    @Test
    void standingOverHttp() {
        String show = newShow(10, 4);
        var api = new ApiClient(port);
        String token = api.userToken("http-fan");

        var ok = api.post("/shows/" + show + "/reserve", token, Map.of("section", "PIT", "quantity", 2));
        var both = api.post("/shows/" + show + "/reserve", token,
                Map.of("seats", List.of("GOLD-A1"), "section", "PIT", "quantity", 1));
        var half = api.post("/shows/" + show + "/reserve", token, Map.of("section", "PIT"));

        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("seats").size()).isEqualTo(2);
        assertThat(ok.body().get("amount_paise").asLong()).isEqualTo(400000L);
        assertThat(both.status()).isEqualTo(400);
        assertThat(half.status()).isEqualTo(400);
        assertThat(half.body().get("error").asString()).isEqualTo("invalid_quantity");
    }

    private static void assertBadRequest(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.body()).containsEntry("error", code);
        });
    }

    private static <T> List<T> race(int n, IntFunction<T> task) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.apply(idx);
                }));
            }
            start.countDown();
            var out = new ArrayList<T>();
            for (var f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}

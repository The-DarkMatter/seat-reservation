package dev.amogh.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

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
import dev.amogh.seats.web.ApiException;

/** POST /reservations/{id}/seats: more seats into an existing hold, on the same timer. */
@IntegrationTest
class AddToHoldTests {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;
    @Autowired
    JdbcClient jdbc;
    @LocalServerPort
    int port;

    private String holdShow(int seats, int limit, int ttl) {
        var labels = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        return shows.create(new CreateShowRequest("add-to-hold", labels, 10000L, limit, ttl)).id();
    }

    private ReservationView hold(String user, String show, String... seats) {
        return ((Reserved) reservations.reserve(user, show, List.of(seats), null)).reservation();
    }

    private ReserveResult add(String user, String reservationId, String... seats) {
        return reservations.addToHold(user, reservationId, new SeatRequest.Named(List.of(seats)), null);
    }

    private LocalDateTime heldUntil(String show, String seat) {
        return jdbc.sql("SELECT held_until FROM seats WHERE show_id = ? AND label = ?")
                .params(show, seat).query(LocalDateTime.class).single();
    }

    @Test
    void addedSeatsJoinTheHoldOnItsOriginalTimer() throws Exception {
        String show = holdShow(10, 6, 300);
        var first = hold("nisha", show, "A1", "A2");
        Thread.sleep(50);

        var result = add("nisha", first.reservationId(), "A5");

        assertThat(result).isInstanceOfSatisfying(Reserved.class, r -> {
            assertThat(r.reservation().reservationId()).isEqualTo(first.reservationId());
            assertThat(r.reservation().seats()).containsExactly("A1", "A2", "A5");
            assertThat(r.reservation().amountPaise()).isEqualTo(30000L);
            assertThat(r.reservation().expiresAt()).isEqualTo(first.expiresAt()); // the timer didn't restart
        });
        assertThat(heldUntil(show, "A5")).isEqualTo(heldUntil(show, "A1"));
        assertThat(reservations.get("nisha", first.reservationId()).seats()).containsExactly("A1", "A2", "A5");

        // Paying confirms every seat in the hold, old and new.
        reservations.confirm("nisha", first.reservationId());
        assertThat(shows.get(show).counts().confirmed()).isEqualTo(3);
    }

    @Test
    void resendingTheSameAddIsANoOp() {
        String show = holdShow(10, 6, 300);
        var first = hold("om", show, "A1");
        var once = (Reserved) add("om", first.reservationId(), "A2");

        var twice = add("om", first.reservationId(), "A2");

        assertThat(twice).isInstanceOfSatisfying(Reserved.class,
                r -> assertThat(r.reservation().seats()).isEqualTo(once.reservation().seats()));
        assertThat(shows.get(show).counts().held()).isEqualTo(2);
    }

    @Test
    void aTakenSeatIsDeclinedAndTheHoldIsUnchanged() {
        String show = holdShow(10, 6, 300);
        hold("someone-else", show, "A3");
        var first = hold("om", show, "A1");

        var result = add("om", first.reservationId(), "A2", "A3");

        assertThat(result).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.SEAT_TAKEN));
        assertThat(reservations.get("om", first.reservationId()).seats()).containsExactly("A1");
        assertThat(shows.get(show).counts().held()).isEqualTo(2); // A1 and someone else's A3; A2 untouched
    }

    @Test
    void thePerUserLimitCountsTheWholeHold() {
        String show = holdShow(10, 3, 300);
        var first = hold("pia", show, "A1", "A2");

        assertThat(add("pia", first.reservationId(), "A3", "A4")).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.PER_USER_LIMIT));
        assertThat(add("pia", first.reservationId(), "A3")).isInstanceOf(Reserved.class);
    }

    @Test
    void onlyALiveHoldOfYourOwnCanGrow() throws Exception {
        String show = holdShow(10, 6, 1);
        var expiring = hold("raj", show, "A1");
        String paidShow = holdShow(10, 6, 300);
        var paid = hold("raj", paidShow, "A1");
        reservations.confirm("raj", paid.reservationId());
        var stranger = hold("raj", paidShow, "A2");

        assertConflict(() -> add("raj", paid.reservationId(), "A3"), "not_a_hold");
        assertThatThrownBy(() -> add("not-raj", stranger.reservationId(), "A4"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
        Thread.sleep(1200);
        assertConflict(() -> add("raj", expiring.reservationId(), "A2"), "hold_expired");
    }

    @Test
    void standingPlacesCanJoinAHoldAndAKeyMakesItSafeToRetry() {
        String show = shows.create(new CreateShowRequest("add-standing", null, null, 8, 300, null, null,
                List.of(new SectionSpec("GOLD", "Gold", 50000L, false, null, List.of(new RowSpec("A", 6, null)), null),
                        new SectionSpec("PIT", "Pit", 20000L, true, 20, null, null)),
                null)).id();
        var first = hold("sam", show, "GOLD-A1");
        var pit = new SeatRequest.Standing("PIT", 2);

        var added = reservations.addToHold("sam", first.reservationId(), pit, "add-pit-1");
        var retried = reservations.addToHold("sam", first.reservationId(), pit, "add-pit-1");

        assertThat(added).isInstanceOfSatisfying(Reserved.class, r -> {
            assertThat(r.reservation().seats()).hasSize(3);
            assertThat(r.reservation().amountPaise()).isEqualTo(50000L + 2 * 20000L);
        });
        assertThat(retried).isInstanceOfSatisfying(Replayed.class, r -> assertThat(r.body()).isEqualTo(added.body()));
        assertThat(shows.get(show).counts().held()).isEqualTo(3);
    }

    @Test
    void twoHoldsRacingForOneSeatGetOneWinner() throws Exception {
        String show = holdShow(30, 6, 300);
        var holds = IntStream.range(0, 20).mapToObj(i -> hold("racer-" + i, show, "A" + (i + 2))).toList();
        var start = new CountDownLatch(1);
        var results = new ArrayList<ReserveResult>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<ReserveResult>>();
            for (int i = 0; i < holds.size(); i++) {
                var h = holds.get(i);
                String user = "racer-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return add(user, h.reservationId(), "A1");
                }));
            }
            start.countDown();
            for (var f : futures) {
                results.add(f.get());
            }
        }
        assertThat(results.stream().filter(r -> r instanceof Reserved).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof Declined).count()).isEqualTo(19);
        assertThat(shows.get(show).counts().held()).isEqualTo(21);
    }

    @Test
    void addOverHttp() {
        String show = holdShow(10, 6, 300);
        var api = new ApiClient(port);
        String token = api.userToken("http-holder");
        var created = api.post("/shows/" + show + "/reserve", token, Map.of("seats", List.of("A1")));
        String id = created.body().get("reservation_id").asString();

        var res = api.post("/reservations/" + id + "/seats", token, Map.of("seats", List.of("A2", "A3")),
                Map.of("Idempotency-Key", "add-1"));
        var replay = api.post("/reservations/" + id + "/seats", token, Map.of("seats", List.of("A2", "A3")),
                Map.of("Idempotency-Key", "add-1"));
        var stranger = api.post("/reservations/" + id + "/seats", api.userToken("not-them"),
                Map.of("seats", List.of("A4")));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("seats").size()).isEqualTo(3);
        assertThat(res.body().get("expires_at").asString()).isEqualTo(created.body().get("expires_at").asString());
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(stranger.status()).isEqualTo(403);
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.body()).containsEntry("error", code);
        });
    }
}

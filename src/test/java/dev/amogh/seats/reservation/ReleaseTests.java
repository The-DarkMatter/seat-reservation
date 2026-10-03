package dev.amogh.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReserveResult.Declined;
import dev.amogh.seats.reservation.ReserveResult.Reserved;
import dev.amogh.seats.show.CreateShowRequest;
import dev.amogh.seats.show.ShowService;
import dev.amogh.seats.web.ApiException;

/** Cancel, holds, confirm, expiry, and the "never resurrect someone else's seat" rule. */
@IntegrationTest
class ReleaseTests {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;
    @Autowired
    HoldSweeper sweeper;
    @Autowired
    JdbcClient jdbc;
    @LocalServerPort
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    private String newShow(Integer holdTtlSeconds) {
        var labels = IntStream.rangeClosed(1, 10).mapToObj(i -> "A" + i).toList();
        return shows.create(new CreateShowRequest("release", labels, 10000L, 4, holdTtlSeconds)).id();
    }

    private Reserved reserve(String user, String show, String... seats) {
        return (Reserved) reservations.reserve(user, show, List.of(seats), null);
    }

    private Map<String, Object> seat(String show, String label) {
        return jdbc.sql("SELECT status, user_id, reservation_id FROM seats WHERE show_id = ? AND label = ?")
                .params(show, label).query().singleRow();
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ---- cancel ---------------------------------------------------------------

    @Test
    void cancelFreesSeatsForSomeoneElse() {
        String show = newShow(null);
        var mine = reserve("alice", show, "A1", "A2");

        var cancelled = reservations.cancel("alice", mine.reservation().reservationId());
        assertThat(cancelled.reservation().status()).isEqualTo("cancelled");
        assertThat(cancelled.seatsMoved()).isEqualTo(2);
        assertThat(seat(show, "A1").get("status")).isEqualTo("available");

        assertThat(reservations.reserve("bob", show, List.of("A1"), null)).isInstanceOf(Reserved.class);
    }

    @Test
    void cancellingTwiceNeverResurrectsSomeoneElsesSeat() {
        String show = newShow(null);
        var alices = reserve("alice", show, "A3");
        reservations.cancel("alice", alices.reservation().reservationId());
        var bobs = reserve("bob", show, "A3");

        var again = reservations.cancel("alice", alices.reservation().reservationId());

        assertThat(again.changed()).isFalse();
        assertThat(again.reservation().status()).isEqualTo("cancelled");
        assertThat(seat(show, "A3").get("status")).isEqualTo("confirmed");
        assertThat(seat(show, "A3").get("reservation_id")).isEqualTo(bobs.reservation().reservationId());
    }

    @Test
    void onlyTheOwnerCanCancel() {
        String show = newShow(null);
        var mine = reserve("alice", show, "A4");

        var res = api.post("/reservations/" + mine.reservation().reservationId() + "/cancel",
                api.userToken("mallory"), Map.of());

        assertThat(res.status()).isEqualTo(403);
        assertThat(res.body().get("error").asString()).isEqualTo("not_owner");
        assertThat(seat(show, "A4").get("user_id")).isEqualTo("alice");
        assertThat(api.post("/reservations/" + mine.reservation().reservationId() + "/cancel", null, Map.of())
                .status()).isEqualTo(401);
    }

    @Test
    void cancelAndRebookRaceHasAtMostOneNewOwner() throws Exception {
        for (int round = 0; round < 5; round++) {
            String show = newShow(null);
            var original = reserve("owner", show, "A5");

            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<ReserveResult>>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var cancel = pool.submit(() -> {
                    start.await();
                    return reservations.cancel("owner", original.reservation().reservationId());
                });
                for (int i = 0; i < 50; i++) {
                    String user = "racer-" + i;
                    results.add(pool.submit(() -> {
                        start.await();
                        return reservations.reserve(user, show, List.of("A5"), null);
                    }));
                }
                start.countDown();
                cancel.get();
                long winners = 0;
                String winnerId = null;
                for (var f : results) {
                    if (f.get() instanceof Reserved r) {
                        winners++;
                        winnerId = r.reservation().reservationId();
                    }
                }
                assertThat(winners).isLessThanOrEqualTo(1);
                var a5 = seat(show, "A5");
                if (winners == 1) {
                    assertThat(a5.get("reservation_id")).isEqualTo(winnerId);
                } else {
                    assertThat(a5.get("status")).isEqualTo("available");
                }
            }
        }
    }

    // ---- holds ----------------------------------------------------------------

    @Test
    void holdModeReservesAsHeldThenConfirms() {
        String show = newShow(60);
        var held = reserve("carol", show, "A1");

        assertThat(held.reservation().status()).isEqualTo("held");
        assertThat(held.reservation().expiresAt()).isNotNull();
        assertThat(shows.get(show).counts().held()).isEqualTo(1);

        var confirmed = reservations.confirm("carol", held.reservation().reservationId());
        assertThat(confirmed.reservation().status()).isEqualTo("confirmed");
        assertThat(confirmed.reservation().expiresAt()).isNull();
        assertThat(shows.get(show).counts().confirmed()).isEqualTo(1);

        // A retried confirm (think: payment callback delivered twice) changes nothing.
        var again = reservations.confirm("carol", held.reservation().reservationId());
        assertThat(again.changed()).isFalse();
        assertThat(again.reservation().status()).isEqualTo("confirmed");
    }

    @Test
    void anExpiredHoldIsFreeImmediatelyAndCantBeConfirmed() throws Exception {
        String show = newShow(1);
        var held = reserve("dave", show, "A2");
        sleep(1300);

        // Reported available and claimable before any sweeper has run.
        assertThat(shows.get(show).counts().held()).isZero();
        var stolen = reserve("erin", show, "A2");

        assertThatThrownBy(() -> reservations.confirm("dave", held.reservation().reservationId()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.body()).containsEntry("error", "hold_expired");
                });

        // Dave cancelling his dead hold must not touch Erin's seat.
        reservations.cancel("dave", held.reservation().reservationId());
        assertThat(seat(show, "A2").get("reservation_id")).isEqualTo(stolen.reservation().reservationId());
        assertThat(seat(show, "A2").get("status")).isEqualTo("held");
    }

    @Test
    void sweeperTidiesExpiredHolds() throws Exception {
        String show = newShow(1);
        var held = reserve("frank", show, "A3", "A4");
        sleep(1300);

        sweeper.sweep();

        assertThat(seat(show, "A3").get("status")).isEqualTo("available");
        assertThat(seat(show, "A4").get("reservation_id")).isNull();
        var row = reservations.get("frank", held.reservation().reservationId());
        assertThat(row.status()).isEqualTo("expired");
        assertThatThrownBy(() -> reservations.confirm("frank", held.reservation().reservationId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void expiredHoldsDontCountTowardTheLimit() throws Exception {
        String show = newShow(1);
        reserve("grace", show, "A1", "A2", "A3", "A4");
        assertThat(reservations.reserve("grace", show, List.of("A5"), null)).isInstanceOf(Declined.class);

        sleep(1300);
        assertThat(reservations.reserve("grace", show, List.of("A5"), null)).isInstanceOf(Reserved.class);
    }

    @Test
    void confirmVersusRebookAtExpiryHasExactlyOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            String show = newShow(1);
            var held = reserve("holder", show, "A6");
            sleep(950); // land the race right around the expiry instant

            var start = new CountDownLatch(1);
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Boolean> confirm = pool.submit(() -> {
                    start.await();
                    try {
                        reservations.confirm("holder", held.reservation().reservationId());
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                });
                var rebooks = new ArrayList<Future<ReserveResult>>();
                for (int i = 0; i < 20; i++) {
                    String user = "rebook-" + i;
                    rebooks.add(pool.submit(() -> {
                        start.await();
                        sleep(60);
                        return reservations.reserve(user, show, List.of("A6"), null);
                    }));
                }
                start.countDown();
                boolean confirmed = confirm.get();
                long rebooked = 0;
                for (var f : rebooks) {
                    if (f.get() instanceof Reserved) {
                        rebooked++;
                    }
                }
                assertThat((confirmed ? 1 : 0) + rebooked).isEqualTo(1);
                var a6 = seat(show, "A6");
                assertThat(a6.get("user_id")).isEqualTo(confirmed ? "holder" : a6.get("user_id"));
                assertThat(a6.get("status")).isNotEqualTo("available");
            }
        }
    }

    @Test
    void confirmAndCancelOverHttp() {
        var show = api.post("/shows", api.adminToken(),
                Map.of("name", "http-holds", "seats", List.of("B1", "B2"), "price_paise", 5000,
                        "hold_ttl_seconds", 120)).body();
        String showId = show.get("id").asString();
        assertThat(show.get("hold_ttl_seconds").asInt()).isEqualTo(120);
        String token = api.userToken("heidi");

        var held = api.post("/shows/" + showId + "/reserve", token, Map.of("seats", List.of("B1")));
        assertThat(held.status()).isEqualTo(201);
        assertThat(held.body().get("status").asString()).isEqualTo("held");
        assertThat(held.body().get("expires_at").asString()).isNotBlank();
        String id = held.body().get("reservation_id").asString();

        assertThat(api.post("/reservations/" + id + "/confirm", api.userToken("ivan"), Map.of()).status())
                .isEqualTo(403);
        var confirmed = api.post("/reservations/" + id + "/confirm", token, Map.of());
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().get("status").asString()).isEqualTo("confirmed");

        var cancelled = api.post("/reservations/" + id + "/cancel", token, Map.of());
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asString()).isEqualTo("cancelled");
        assertThat(api.post("/reservations/" + id + "/confirm", token, Map.of()).status()).isEqualTo(409);
        assertThat(api.post("/reservations/nope/cancel", token, Map.of()).status()).isEqualTo(404);
    }
}

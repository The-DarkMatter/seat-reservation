package dev.amogh.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReserveResult.Declined;
import dev.amogh.seats.reservation.ReserveResult.Reserved;
import dev.amogh.seats.show.CreateShowRequest;
import dev.amogh.seats.show.CreateShowRequest.SectionSpec;
import dev.amogh.seats.show.ShowService;

/** allow_partial: book what's available instead of all-or-nothing. */
@IntegrationTest
class PartialBookingTests {

    @Autowired
    ReservationService reservations;
    @Autowired
    ShowService shows;
    @LocalServerPort
    int port;

    private String flatShow(int seats, int limit) {
        var labels = java.util.stream.IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        return shows.create(new CreateShowRequest("partial", labels, 10000L, limit, null)).id();
    }

    private String pitShow(int capacity) {
        return shows.create(new CreateShowRequest("partial-pit", null, null, 10, null, null, null,
                List.of(new SectionSpec("PIT", "Pit", 20000L, true, capacity, null, null)), null)).id();
    }

    @Test
    void partialNamedBookingTakesTheFreeSeatsAndListsTheRest() {
        String show = flatShow(4, 4);
        reservations.reserve("other", show, List.of("A2"), null);

        var result = reservations.reserve("pooja", show, new SeatRequest.Named(List.of("A3", "A1", "A2"), true), null);

        assertThat(result).isInstanceOfSatisfying(Reserved.class, r -> {
            assertThat(r.reservation().seats()).containsExactly("A3", "A1");
            assertThat(r.reservation().amountPaise()).isEqualTo(20000L);
            assertThat(r.reservation().shortfall()).isEqualTo(new ReservationView.Shortfall(3, List.of("A2")));
        });
    }

    @Test
    void defaultIsStillAllOrNothing() {
        String show = flatShow(4, 4);
        reservations.reserve("other", show, List.of("A2"), null);

        var result = reservations.reserve("pooja", show, List.of("A1", "A2"), null);

        assertThat(result).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.SEAT_TAKEN));
        assertThat(shows.get(show).counts().confirmed()).isEqualTo(1);
    }

    @Test
    void partialWithNothingLeftIsStillADecline() {
        String show = flatShow(2, 4);
        reservations.reserve("other", show, List.of("A1", "A2"), null);

        var result = reservations.reserve("pooja", show, new SeatRequest.Named(List.of("A1", "A2"), true), null);

        assertThat(result).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.SEAT_TAKEN));
    }

    @Test
    void partialStandingTakesWhatIsLeft() {
        String show = pitShow(5);
        reservations.reserve("early", show, new SeatRequest.Standing("PIT", 3), null);

        var partial = reservations.reserve("late", show, new SeatRequest.Standing("PIT", 4, true), null);
        var nothingLeft = reservations.reserve("later", show, new SeatRequest.Standing("PIT", 1, true), null);

        assertThat(partial).isInstanceOfSatisfying(Reserved.class, r -> {
            assertThat(r.reservation().seats()).hasSize(2);
            assertThat(r.reservation().amountPaise()).isEqualTo(40000L);
            assertThat(r.reservation().shortfall()).isEqualTo(new ReservationView.Shortfall(4, null));
        });
        assertThat(nothingLeft).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.SECTION_SOLD_OUT));
    }

    @Test
    void partialAndAllOrNothingAreDifferentRequestsForIdempotency() {
        String show = flatShow(4, 4);
        reservations.reserve("kabir", show, List.of("A1"), "same-key");

        var flipped = reservations.reserve("kabir", show, new SeatRequest.Named(List.of("A1"), true), "same-key");

        assertThat(flipped).isInstanceOfSatisfying(Declined.class,
                d -> assertThat(d.reason()).isEqualTo(DeclineReason.IDEMPOTENCY_KEY_REUSE));
    }

    @Test
    void partialRaceStillSellsEachSeatOnce() throws Exception {
        String show = flatShow(5, 5);
        var everything = List.of("A1", "A2", "A3", "A4", "A5");
        var start = new CountDownLatch(1);
        var results = new ArrayList<ReserveResult>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<ReserveResult>>();
            for (int i = 0; i < 60; i++) {
                String user = "grab-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return reservations.reserve(user, show, new SeatRequest.Named(everything, true), null);
                }));
            }
            start.countDown();
            for (var f : futures) {
                results.add(f.get());
            }
        }

        var sold = new ArrayList<String>();
        results.stream().filter(r -> r instanceof Reserved)
                .forEach(r -> sold.addAll(((Reserved) r).reservation().seats()));
        assertThat(sold).hasSize(5);
        assertThat(new HashSet<>(sold)).hasSize(5);
        assertThat(shows.get(show).counts().confirmed()).isEqualTo(5);
    }

    @Test
    void allowPartialOverHttp() {
        String show = flatShow(3, 4);
        var api = new ApiClient(port);
        api.post("/shows/" + show + "/reserve", api.userToken("first"), Map.of("seats", List.of("A2")));

        var res = api.post("/shows/" + show + "/reserve", api.userToken("second"),
                Map.of("seats", List.of("A1", "A2", "A3"), "allow_partial", true));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("seats").size()).isEqualTo(2);
        assertThat(res.body().get("shortfall").get("requested").asInt()).isEqualTo(3);
        assertThat(res.body().get("shortfall").get("unavailable").get(0).asString()).isEqualTo("A2");
    }
}

package dev.amogh.seats.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReservationService;
import dev.amogh.seats.reservation.ReserveResult;
import dev.amogh.seats.reservation.SeatRequest;

/** The read endpoints the UI uses: listing, live seat map, and "my reservations". */
@IntegrationTest
class BrowseApiTests {

    @Autowired
    ShowService shows;
    @Autowired
    ReservationService reservations;
    @LocalServerPort
    int port;

    @Test
    void featuredListingShowsCountsPerSectionWithoutAToken() {
        var show = shows.create(SectionTests.arena("Monsoon Raag Live"), "featured");
        reservations.reserve("listing-fan", show.id(), new SeatRequest.Standing("SILVER", 2), null);
        var api = new ApiClient(port);

        var res = api.get("/shows?kind=featured", null);

        assertThat(res.status()).isEqualTo(200);
        var entry = findById(res.body(), show.id());
        assertThat(entry.get("name").asString()).isEqualTo("Monsoon Raag Live");
        assertThat(entry.get("venue").asString()).isEqualTo("Kursi Arena, Mumbai");
        assertThat(entry.get("counts").get("confirmed").asInt()).isEqualTo(2);
        assertThat(entry.get("sections").get(1).get("counts").get("available").asInt()).isEqualTo(148);
        assertThat(entry.has("seats")).isFalse();
        assertThat(api.get("/shows?kind=demo", null).status()).isEqualTo(400);
        assertThat(api.get("/shows?limit=500", null).status()).isEqualTo(400);
    }

    @Test
    void apiShowsAreNotListedAsFeatured() {
        var show = shows.create(SectionTests.arena("plain api show"));
        var api = new ApiClient(port);

        var featured = api.get("/shows", null).body();
        var all = api.get("/shows?kind=api&limit=50", null).body();

        assertThat(featured.findValuesAsString("id")).doesNotContain(show.id());
        assertThat(all.findValuesAsString("id")).contains(show.id());
    }

    @Test
    void seatMapIsOneCharacterPerSeatInLayoutOrder() {
        var show = shows.create(SectionTests.arena("seatmap"));
        reservations.reserve("map-fan", show.id(), List.of("GOLD-X1", "GOLD-Y18"), null);
        reservations.reserve("map-fan", show.id(), new SeatRequest.Standing("SILVER", 3), null);
        var api = new ApiClient(port);

        var res = api.get("/shows/" + show.id() + "/seatmap", null);

        assertThat(res.status()).isEqualTo(200);
        var gold = res.body().get("sections").get(0);
        String states = gold.get("states").asString();
        assertThat(states).hasSize(38).startsWith("ca").endsWith("ac");
        assertThat(gold.get("counts").get("confirmed").asInt()).isEqualTo(2);
        var silver = res.body().get("sections").get(1);
        assertThat(silver.has("states")).isFalse();
        assertThat(silver.get("counts").get("confirmed").asInt()).isEqualTo(3);
        assertThat(res.body().get("counts").get("available").asInt()).isEqualTo(188 - 5);
        assertThat(api.get("/shows/00000000-0000-0000-0000-000000000000/seatmap", null).status()).isEqualTo(404);
    }

    @Test
    void myReservationsAreMineOnlyAndFilterByShow() throws Exception {
        var a = shows.create(SectionTests.arena("mine-a"));
        var b = shows.create(SectionTests.arena("mine-b"));
        var api = new ApiClient(port);
        String token = api.userToken("asha");
        api.post("/shows/" + a.id() + "/reserve", token, Map.of("seats", List.of("GOLD-X5")));
        Thread.sleep(5); // created_at has microsecond precision; keep the order unambiguous
        api.post("/shows/" + b.id() + "/reserve", token, Map.of("section", "SILVER", "quantity", 1));

        var all = api.get("/me/reservations", token).body();
        var onlyA = api.get("/me/reservations?show_id=" + a.id(), token).body();
        var someoneElse = api.get("/me/reservations", api.userToken("not-asha")).body();

        assertThat(all.size()).isEqualTo(2);
        assertThat(all.get(0).get("show_id").asString()).isEqualTo(b.id()); // newest first
        assertThat(onlyA.size()).isEqualTo(1);
        assertThat(onlyA.get(0).get("seats").get(0).asString()).isEqualTo("GOLD-X5");
        assertThat(someoneElse.size()).isZero();
        assertThat(api.get("/me/reservations", null).status()).isEqualTo(401);
    }

    @Test
    void aHoldPastItsExpiryReadsAsExpired() throws Exception {
        var show = shows.create(new CreateShowRequest("short-hold", List.of("A1"), 100L, null, 1));
        var held = (ReserveResult.Reserved) reservations.reserve("tara", show.id(), List.of("A1"), null);
        assertThat(held.reservation().status()).isEqualTo("held");

        Thread.sleep(1200);

        var view = reservations.get("tara", held.reservation().reservationId());
        assertThat(view.status()).isEqualTo("expired");
        assertThat(view.expiresAt()).isNull();
    }

    private static tools.jackson.databind.JsonNode findById(tools.jackson.databind.JsonNode list, String id) {
        for (var node : list) {
            if (node.get("id").asString().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("show " + id + " not in listing: " + list);
    }
}

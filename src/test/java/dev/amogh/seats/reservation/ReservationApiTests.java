package dev.amogh.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;

/** The HTTP contract: status codes, body shapes, identity from the token only. */
@IntegrationTest
class ReservationApiTests {

    @LocalServerPort
    int port;

    ApiClient api;
    String showId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        var show = api.post("/shows", api.adminToken(),
                Map.of("name", "api", "seats", List.of("A1", "A2", "A3", "A12", "A13"), "price_paise", 25000));
        showId = show.body().get("id").asString();
    }

    private ApiClient.Response reserve(String token, Object body) {
        return api.post("/shows/" + showId + "/reserve", token, body);
    }

    @Test
    void successMatchesTheSpecShape() {
        var res = reserve(api.userToken("alice"), Map.of("seats", List.of("A12"), "idempotency_key", "k1"));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("reservation_id").asString()).isNotBlank();
        assertThat(res.body().get("show_id").asString()).isEqualTo(showId);
        assertThat(res.body().get("user_id").asString()).isEqualTo("alice");
        assertThat(res.body().get("seats").get(0).asString()).isEqualTo("A12");
        assertThat(res.body().get("amount_paise").asLong()).isEqualTo(25000);
        assertThat(res.body().get("status").asString()).isEqualTo("confirmed");
        assertThat(res.body().has("expires_at")).isFalse();
    }

    @Test
    void amountIsPriceTimesSeats() {
        var res = reserve(api.userToken("bulk"), Map.of("seats", List.of("A1", "A2", "A3")));
        assertThat(res.body().get("amount_paise").asLong()).isEqualTo(75000);
    }

    @Test
    void takenSeatIsACleanConflict() {
        reserve(api.userToken("first"), Map.of("seats", List.of("A1")));

        var res = reserve(api.userToken("second"), Map.of("seats", List.of("A1")));
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body().get("error").asString()).isEqualTo("seat_taken");
        assertThat(res.body().get("seats").get(0).asString()).isEqualTo("A1");
    }

    @Test
    void spoofedUserIdInBodyIsIgnored() {
        var res = reserve(api.userToken("mallory"),
                Map.of("seats", List.of("A2"), "user_id", "victim", "userId", "victim"));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("user_id").asString()).isEqualTo("mallory");
    }

    @Test
    void idempotencyKeyWorksFromHeaderOrBody() {
        String token = api.userToken("carol");
        var first = api.post("/shows/" + showId + "/reserve", token, Map.of("seats", List.of("A13")),
                Map.of("Idempotency-Key", "hdr-1"));
        var retry = reserve(token, Map.of("seats", List.of("A13"), "idempotency_key", "hdr-1"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.raw()).isEqualTo(first.raw());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");

        var reuse = reserve(token, Map.of("seats", List.of("A3"), "idempotency_key", "hdr-1"));
        assertThat(reuse.status()).isEqualTo(409);
        assertThat(reuse.body().get("error").asString()).isEqualTo("idempotency_key_reuse");

        var mismatch = api.post("/shows/" + showId + "/reserve", token,
                Map.of("seats", List.of("A3"), "idempotency_key", "a"), Map.of("Idempotency-Key", "b"));
        assertThat(mismatch.status()).isEqualTo(400);
    }

    @Test
    void keysAreScopedPerUser() {
        reserve(api.userToken("dave"), Map.of("seats", List.of("A1"), "idempotency_key", "shared"));

        // Eve using the same key string is a different key: she gets her own outcome, not Dave's reservation.
        var eve = reserve(api.userToken("eve"), Map.of("seats", List.of("A2"), "idempotency_key", "shared"));
        assertThat(eve.status()).isEqualTo(201);
        assertThat(eve.body().get("user_id").asString()).isEqualTo("eve");
    }

    @Test
    void overLimitIsAConflictNotAnError() {
        String token = api.userToken("frank");
        var res = reserve(token, Map.of("seats", List.of("A1", "A2", "A3", "A12", "A13")));
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body().get("error").asString()).isEqualTo("per_user_limit");
        assertThat(res.body().get("per_user_limit").asInt()).isEqualTo(4);
    }

    @Test
    void badRequestsAre4xx() {
        String token = api.userToken("grace");
        assertThat(reserve(token, Map.of("seats", List.of())).status()).isEqualTo(400);
        assertThat(reserve(token, Map.of("seats", List.of("Z9"))).body().get("error").asString())
                .isEqualTo("unknown_seats");
        assertThat(reserve(token, Map.of("seats", List.of("A1", "A1"))).body().get("error").asString())
                .isEqualTo("duplicate_seats");
        assertThat(reserve(token, "{oops").status()).isEqualTo(400);
        assertThat(reserve(null, Map.of("seats", List.of("A1"))).status()).isEqualTo(401);
        assertThat(api.post("/shows/00000000-0000-0000-0000-000000000000/reserve", token,
                Map.of("seats", List.of("A1"))).status()).isEqualTo(404);
    }

    @Test
    void onlyTheOwnerCanReadAReservation() {
        var made = reserve(api.userToken("heidi"), Map.of("seats", List.of("A3")));
        String id = made.body().get("reservation_id").asString();

        assertThat(api.get("/reservations/" + id, api.userToken("heidi")).status()).isEqualTo(200);
        assertThat(api.get("/reservations/" + id, api.userToken("ivan")).status()).isEqualTo(403);
        assertThat(api.get("/reservations/nope", api.userToken("heidi")).status()).isEqualTo(404);
    }

    @Test
    void showStateReflectsReservations() {
        reserve(api.userToken("judy"), Map.of("seats", List.of("A1", "A2")));

        var show = api.get("/shows/" + showId, null).body();
        assertThat(show.get("counts").get("confirmed").asInt()).isEqualTo(2);
        assertThat(show.get("counts").get("available").asInt()).isEqualTo(3);
        assertThat(show.get("seats").get(0).get("seat").asString()).isEqualTo("A1");
        assertThat(show.get("seats").get(0).get("status").asString()).isEqualTo("confirmed");
    }
}

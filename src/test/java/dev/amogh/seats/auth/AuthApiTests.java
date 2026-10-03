package dev.amogh.seats.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;

@IntegrationTest
class AuthApiTests {

    @LocalServerPort
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    private Map<String, Object> show(Object price) {
        return Map.of("name", "auth-test", "seats", List.of("A1", "A2"), "price_paise", price);
    }

    @Test
    void mintsUserTokens() {
        var res = api.post("/auth/token", null, Map.of("user_id", "alice"));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("token").asString()).isNotBlank();
        assertThat(res.body().get("role").asString()).isEqualTo("user");
        assertThat(res.body().get("expires_in").asLong()).isPositive();
    }

    @Test
    void rejectsBadUserIdsAndWrongAdminSecret() {
        assertThat(api.post("/auth/token", null, Map.of("user_id", "")).status()).isEqualTo(400);
        assertThat(api.post("/auth/token", null, Map.of("user_id", "has space")).status()).isEqualTo(400);
        assertThat(api.post("/auth/token", null, Map.of("user_id", "x", "role", "root")).status()).isEqualTo(400);
        var wrong = api.post("/auth/token", null,
                Map.of("user_id", "x", "role", "admin", "admin_secret", "nope"));
        assertThat(wrong.status()).isEqualTo(403);
        assertThat(wrong.body().get("error").asString()).isEqualTo("invalid_admin_secret");
    }

    @Test
    void creatingShowsNeedsAnAdminToken() {
        assertThat(api.post("/shows", null, show(100)).status()).isEqualTo(401);
        assertThat(api.post("/shows", "garbage.token.here", show(100)).status()).isEqualTo(401);

        var asUser = api.post("/shows", api.userToken("bob"), show(100));
        assertThat(asUser.status()).isEqualTo(403);
        assertThat(asUser.body().get("error").asString()).isEqualTo("forbidden");

        var asAdmin = api.post("/shows", api.adminToken(), show(100));
        assertThat(asAdmin.status()).isEqualTo(201);
        assertThat(asAdmin.body().get("counts").get("available").asInt()).isEqualTo(2);
    }

    @Test
    void showStateIsPublic() {
        var created = api.post("/shows", api.adminToken(), show(100));
        var res = api.get("/shows/" + created.body().get("id").asString(), null);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("total_seats").asInt()).isEqualTo(2);
        assertThat(api.get("/shows/does-not-exist", null).status()).isEqualTo(404);
    }

    @Test
    void moneyIsIntegerPaiseNeverFloat() {
        var res = api.post("/shows", api.adminToken(), show(250.5));
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().get("error").asString()).isEqualTo("malformed_request");
    }

    @Test
    void malformedJsonIsA400NotA500() {
        var res = api.post("/shows", api.adminToken(), "{not json");
        assertThat(res.status()).isEqualTo(400);
    }
}

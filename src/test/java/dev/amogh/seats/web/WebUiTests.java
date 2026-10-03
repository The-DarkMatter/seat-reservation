package dev.amogh.seats.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.IntegrationTest;

/** The UI and the API share "/": browsers get the app, everything else gets JSON. */
@IntegrationTest
class WebUiTests {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String accept) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void browsersGetTheAppAtTheRoot() throws Exception {
        var res = get("/", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("Kursi test page");
        assertThat(res.headers().firstValue("Content-Security-Policy").orElse(""))
                .contains("default-src 'self'")
                .contains("frame-ancestors 'self' https://amogh.cloud");
        assertThat(res.headers().firstValue("X-Frame-Options")).isEmpty();
    }

    @Test
    void apiClientsStillGetTheJsonIndex() throws Exception {
        for (String accept : new String[] {"*/*", "application/json"}) {
            var res = get("/", accept);
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/json");
            assertThat(res.body()).contains("\"service\":\"seat-reservation\"");
        }
    }

    @Test
    void appRoutesServeTheAppSoDeepLinksWork() throws Exception {
        for (String path : new String[] {"/events/123", "/checkout/abc", "/tickets/abc", "/me", "/lab"}) {
            var res = get(path, "text/html");
            assertThat(res.statusCode()).as(path).isEqualTo(200);
            assertThat(res.body()).as(path).contains("Kursi test page");
        }
    }

    @Test
    void apiRoutesUnderTheSamePrefixesAreUnchanged() throws Exception {
        assertThat(get("/me/reservations", "application/json").statusCode()).isEqualTo(401);
        assertThat(get("/shows/00000000-0000-0000-0000-000000000000", "application/json").statusCode()).isEqualTo(404);
    }
}

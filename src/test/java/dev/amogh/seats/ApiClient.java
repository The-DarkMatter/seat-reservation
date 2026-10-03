package dev.amogh.seats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tiny real-HTTP client for integration tests (goes through Tomcat + the security chain). */
public class ApiClient {

    public record Response(int status, JsonNode body, String raw, HttpHeaders headers) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final JsonMapper json = JsonMapper.builder().build();

    public ApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    public Response get(String path, String token) {
        return send(request(path, token).GET().build());
    }

    public Response post(String path, String token, Object body) {
        return post(path, token, body, Map.of());
    }

    public Response post(String path, String token, Object body, Map<String, String> headers) {
        String payload = body instanceof String s ? s : json.writeValueAsString(body);
        var builder = request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    public String userToken(String userId) {
        var res = post("/auth/token", null, Map.of("user_id", userId));
        if (res.status() != 200) {
            throw new IllegalStateException("token mint failed: " + res.raw());
        }
        return res.body().get("token").asString();
    }

    public String adminToken() {
        var res = post("/auth/token", null,
                Map.of("user_id", "admin", "role", "admin", "admin_secret", "demo-admin-secret"));
        if (res.status() != 200) {
            throw new IllegalStateException("admin token mint failed: " + res.raw());
        }
        return res.body().get("token").asString();
    }

    private HttpRequest.Builder request(String path, String token) {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private Response send(HttpRequest req) {
        try {
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String raw = res.body();
            boolean isJson = res.headers().firstValue("Content-Type").orElse("").contains("json");
            JsonNode body = !isJson || raw == null || raw.isBlank() ? null : json.readTree(raw);
            return new Response(res.statusCode(), body, raw, res.headers());
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + req.uri(), e);
        }
    }
}

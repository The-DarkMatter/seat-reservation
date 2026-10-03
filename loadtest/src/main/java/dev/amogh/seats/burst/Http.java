package dev.amogh.seats.burst;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A thin HTTP/1.1 client with keep-alive and a cap on in-flight requests.
 * Transport failures (timeouts, resets) are returned as status 0 rather than
 * thrown, so they show up in the report instead of killing the run.
 */
final class Http {

    record Res(int status, JsonNode body, String raw, boolean replayed, long nanos) {
        String error() {
            return body != null && body.has("error") ? body.get("error").asString() : null;
        }

        String text(String field) {
            return body != null && body.has(field) ? body.get(field).asString() : null;
        }
    }

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;
    private final Semaphore inFlight;
    private final HttpClient client;

    Http(String base, int maxInFlight) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.inFlight = new Semaphore(maxInFlight);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    String base() {
        return base;
    }

    Res get(String path, String token) {
        return send(builder(path, token).GET().build());
    }

    Res post(String path, String token, Object body) {
        return post(path, token, body, Map.of());
    }

    Res post(String path, String token, Object body, Map<String, String> headers) {
        var b = builder(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(b::header);
        return send(b.build());
    }

    String getText(String path) {
        var r = send(builder(path, null).GET().build());
        return r.raw();
    }

    private HttpRequest.Builder builder(String path, String token) {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private Res send(HttpRequest req) {
        inFlight.acquireUninterruptibly();
        long start = System.nanoTime();
        try {
            var res = client.send(req, HttpResponse.BodyHandlers.ofString());
            long nanos = System.nanoTime() - start;
            String raw = res.body();
            JsonNode body = null;
            if (raw != null && !raw.isBlank() && res.headers().firstValue("Content-Type").orElse("").contains("json")) {
                try {
                    body = JSON.readTree(raw);
                } catch (RuntimeException ignored) {
                    // leave body null; the status still counts
                }
            }
            boolean replayed = res.headers().firstValue("Idempotent-Replayed").isPresent();
            return new Res(res.statusCode(), body, raw, replayed, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Res(0, null, "interrupted", false, System.nanoTime() - start);
        } catch (Exception e) {
            return new Res(0, null, e.getClass().getSimpleName() + ": " + e.getMessage(), false,
                    System.nanoTime() - start);
        } finally {
            inFlight.release();
        }
    }
}

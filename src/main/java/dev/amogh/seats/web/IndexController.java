package dev.amogh.seats.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A landing response so a human opening the base URL sees what this is. */
@RestController
public class IndexController {

    private final String version;

    public IndexController(@Value("${info.app.version:dev}") String version) {
        this.version = version;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        var endpoints = new LinkedHashMap<String, String>();
        endpoints.put("POST /auth/token", "mint a bearer token: {\"user_id\": \"alice\"}");
        endpoints.put("POST /shows", "create a show (admin token)");
        endpoints.put("GET /shows/{id}", "per-seat status and counts");
        endpoints.put("POST /shows/{id}/reserve", "reserve seats: {\"seats\": [\"A12\"], \"idempotency_key\": \"...\"}");
        endpoints.put("GET /reservations/{id}", "your reservation");
        endpoints.put("POST /reservations/{id}/confirm", "confirm a hold (hold-mode shows)");
        endpoints.put("POST /reservations/{id}/cancel", "cancel your reservation");
        endpoints.put("GET /health/live", "liveness");
        endpoints.put("GET /health/ready", "readiness (checks MySQL)");
        endpoints.put("GET /metrics", "Prometheus metrics");

        var body = new LinkedHashMap<String, Object>();
        body.put("service", "seat-reservation");
        body.put("version", version);
        body.put("endpoints", endpoints);
        return body;
    }
}

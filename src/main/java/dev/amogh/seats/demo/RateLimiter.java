package dev.amogh.seats.demo;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.amogh.seats.web.ApiException;

/**
 * A token bucket per (action, client IP), in memory. Only the public demo
 * endpoints use it (creating shows, starting rushes): the reservation API itself
 * is never rate limited, because a load test against it is the point.
 *
 * The client IP is the request's remote address, which Tomcat takes from Caddy's
 * X-Forwarded-For (server.forward-headers-strategy=native trusts private-range
 * proxies only, and Caddy discards any X-Forwarded-For a client sends itself).
 */
@Component
public class RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    /** Allows {@code capacity} calls per {@code window}, refilling continuously. */
    public void check(String action, String clientIp, int capacity, Duration window) {
        Bucket bucket = buckets.get(action + "|" + clientIp, k -> new Bucket(capacity));
        long waitMs = bucket.take(capacity, window.toMillis());
        if (waitMs > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Easy there! Try again in a little while.",
                    Map.of("retry_after_seconds", Math.max(1, waitMs / 1000)));
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill = System.nanoTime();

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        /** Takes a token; returns 0, or how long until one is available. */
        synchronized long take(int capacity, long windowMs) {
            long now = System.nanoTime();
            double perMs = (double) capacity / windowMs;
            tokens = Math.min(capacity, tokens + (now - lastRefill) / 1_000_000.0 * perMs);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / perMs);
        }
    }
}

package dev.amogh.seats.show;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.amogh.seats.web.ApiException;

/**
 * In-memory cache of show facts (price, limit, hold TTL, seat labels).
 *
 * Safe because those facts never change after creation, so there is nothing to
 * invalidate. It saves a DB round trip on every reserve during a burst and lets
 * us reject unknown seat labels before opening a transaction. Seat STATE is
 * never cached: that always comes from MySQL.
 */
@Component
public class ShowCatalog {

    private final ShowRepository repository;
    private final Cache<String, Show> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(6))
            .build();

    public ShowCatalog(ShowRepository repository) {
        this.repository = repository;
    }

    /** The show, or a 404. Unknown ids are not cached (the loader returns null). */
    public Show require(String showId) {
        if (!ShowIds.isValid(showId)) {
            throw notFound(showId);
        }
        Show show = cache.get(showId, id -> repository.findShow(id).orElse(null));
        if (show == null) {
            throw notFound(showId);
        }
        return show;
    }

    void remember(Show show) {
        cache.put(show.id(), show);
    }

    private static ApiException notFound(String showId) {
        return ApiException.notFound("show_not_found", "No show with id " + showId);
    }
}

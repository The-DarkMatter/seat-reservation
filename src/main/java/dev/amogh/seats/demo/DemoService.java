package dev.amogh.seats.demo;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.amogh.seats.demo.VenueTemplates.VenueTemplate;
import dev.amogh.seats.show.ShowCatalog;
import dev.amogh.seats.show.ShowService;
import dev.amogh.seats.show.ShowView;
import dev.amogh.seats.web.ApiException;

/**
 * Keeps the public demo alive and tidy:
 * <ul>
 *   <li>one featured show per venue template, swapped for a fresh copy once it
 *       is mostly sold or a few hours old (the old one is hidden, not deleted,
 *       so anyone holding a ticket can still open it);</li>
 *   <li>visitors' own "rush" shows, created on demand;</li>
 *   <li>a cleanup that deletes demo and retired featured shows after a day.</li>
 * </ul>
 * None of this touches shows created through POST /shows (kind = api).
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    static final int MIN_HOLD_TTL = 30;
    static final int MAX_HOLD_TTL = 900;

    private final VenueTemplates templates;
    private final ShowService shows;
    private final ShowCatalog catalog;
    private final DemoRepository repository;
    private final DemoProperties props;
    private final TransactionTemplate tx;

    public DemoService(VenueTemplates templates, ShowService shows, ShowCatalog catalog, DemoRepository repository,
                       DemoProperties props, PlatformTransactionManager txManager) {
        this.templates = templates;
        this.shows = shows;
        this.catalog = catalog;
        this.repository = repository;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public record DemoShow(String id, String name, String template) {
    }

    public List<VenueTemplate> templates() {
        return templates.all();
    }

    /** POST /demo/shows: a visitor's own copy of a venue, to rush with bots or share with friends. */
    public DemoShow createDemoShow(String templateId, Integer holdTtlSeconds) {
        VenueTemplate template = templates.find(templateId == null ? "" : templateId)
                .orElseThrow(() -> ApiException.badRequest("unknown_template",
                        "template must be one of " + templates.all().stream().map(VenueTemplate::id).toList()));
        if (holdTtlSeconds != null && (holdTtlSeconds < MIN_HOLD_TTL || holdTtlSeconds > MAX_HOLD_TTL)) {
            throw ApiException.badRequest("invalid_hold_ttl",
                    "hold_ttl_seconds must be between " + MIN_HOLD_TTL + " and " + MAX_HOLD_TTL);
        }
        if (repository.countDemoShows() >= props.maxLiveDemoShows()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "demo_capacity",
                    "The demo is busy right now; try one of the featured events instead", null);
        }
        String name = "Your rush: " + template.show().name();
        ShowView show = shows.create(template.toRequest(name, holdTtlSeconds), "demo");
        log.atInfo().addKeyValue("event", "demo_show_created").addKeyValue("show_id", show.id())
                .addKeyValue("template", template.id()).log("demo show {} from {}", show.id(), template.id());
        return new DemoShow(show.id(), show.name(), template.id());
    }

    /**
     * Ensures each template has a live featured show with seats left. Runs at
     * startup and then every minute. Single app instance, so no coordination
     * needed; with several instances this would take a DB lock first.
     */
    @Scheduled(initialDelay = 0, fixedDelayString = "${app.demo.featured-check:60s}")
    public void refreshFeatured() {
        if (!props.featured()) {
            return;
        }
        for (VenueTemplate template : templates.all()) {
            try {
                String name = template.show().name();
                var current = repository.latestFeatured(name);
                boolean stale = current.isEmpty()
                        || current.get().ageSeconds() > props.rotateAfter().toSeconds()
                        || current.get().taken() >= props.rotateWhenSold() * current.get().totalSeats();
                if (stale) {
                    ShowView fresh = shows.create(template.toRequest(name, null), "featured");
                    repository.hideOtherFeatured(name, fresh.id());
                    shows.invalidateListings();
                    log.atInfo().addKeyValue("event", "featured_rotated").addKeyValue("show_id", fresh.id())
                            .addKeyValue("template", template.id()).log("featured {} is now {}", name, fresh.id());
                }
            } catch (RuntimeException e) {
                log.warn("could not refresh featured show for {}: {}", template.id(), e.getMessage());
            }
        }
    }

    /** Deletes expired demo shows (one per transaction) and old idempotency keys. */
    @Scheduled(initialDelayString = "${app.demo.cleanup-every:10m}", fixedDelayString = "${app.demo.cleanup-every:10m}")
    public void cleanup() {
        try {
            long retention = props.retention().toSeconds();
            int deleted = 0;
            for (String id : repository.expiredShows(retention, 200)) {
                tx.executeWithoutResult(s -> repository.deleteShow(id));
                catalog.forget(id);
                deleted++;
            }
            int keys = 0;
            for (int batch; (batch = repository.deleteOldIdempotencyKeys(retention, 5000)) > 0; ) {
                keys += batch;
            }
            if (deleted > 0 || keys > 0) {
                log.atInfo().addKeyValue("event", "demo_cleanup").addKeyValue("shows_deleted", deleted)
                        .addKeyValue("idempotency_keys_deleted", keys)
                        .log("cleanup removed {} shows and {} idempotency keys", deleted, keys);
            }
        } catch (RuntimeException e) {
            log.warn("demo cleanup failed: {}", e.getMessage());
        }
    }
}

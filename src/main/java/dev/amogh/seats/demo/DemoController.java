package dev.amogh.seats.demo;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.amogh.seats.demo.DemoService.DemoShow;
import dev.amogh.seats.demo.RushService.RushStatus;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/** The public demo: venue templates, your own show, and a rush of bots. No token needed. */
@RestController
public class DemoController {

    private final DemoService demo;
    private final RushService rush;
    private final RateLimiter limiter;
    private final DemoProperties props;

    public DemoController(DemoService demo, RushService rush, RateLimiter limiter, DemoProperties props) {
        this.demo = demo;
        this.rush = rush;
        this.limiter = limiter;
        this.props = props;
    }

    public record TemplateView(String id, String category, String name, String venue, JsonNode poster) {
    }

    public record CreateDemoShow(String template, Integer holdTtlSeconds) {
    }

    public record StartRush(Integer bots) {
    }

    @GetMapping("/demo/templates")
    public List<TemplateView> templates() {
        return demo.templates().stream()
                .map(t -> new TemplateView(t.id(), t.category(), t.show().name(), t.show().venue(), t.poster()))
                .toList();
    }

    @PostMapping("/demo/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoShow create(@RequestBody CreateDemoShow body, HttpServletRequest request) {
        limiter.check("create", request.getRemoteAddr(), props.createsPerWindow(), props.limitWindow());
        return demo.createDemoShow(body.template(), body.holdTtlSeconds());
    }

    @PostMapping("/demo/shows/{showId}/rush")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RushStatus startRush(@PathVariable String showId, @RequestBody(required = false) StartRush body,
                                HttpServletRequest request) {
        limiter.check("rush", request.getRemoteAddr(), props.rushesPerWindow(), props.limitWindow());
        int bots = body == null || body.bots() == null ? 600 : body.bots();
        return rush.start(showId, bots);
    }

    @GetMapping("/demo/shows/{showId}/rush")
    public RushStatus rushStatus(@PathVariable String showId) {
        return rush.status(showId);
    }
}

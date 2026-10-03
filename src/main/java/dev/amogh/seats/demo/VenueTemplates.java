package dev.amogh.seats.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import dev.amogh.seats.show.CreateShowRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The demo venues (classpath {@code demo/venues/*.json}): an arena with standing
 * pits, a theatre, and a small comedy club. Each file is a POST /shows body plus
 * when the event should be dated relative to today.
 */
@Component
public class VenueTemplates {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record VenueTemplate(String id, String category, int startsInDays, String localTime,
                                CreateShowRequest show) {

        /** The template as a create request: dated from today, tagged with its id and category. */
        public CreateShowRequest toRequest(String name, Integer holdTtlSeconds) {
            Instant startsAt = LocalDate.now(IST).plusDays(startsInDays)
                    .atTime(LocalTime.parse(localTime)).atZone(IST).toInstant();
            ObjectNode layout = ((ObjectNode) show.layout()).deepCopy();
            layout.put("template", id);
            layout.put("category", category);
            return new CreateShowRequest(name, null, null, show.perUserLimit(),
                    holdTtlSeconds == null ? show.holdTtlSeconds() : holdTtlSeconds,
                    show.venue(), startsAt, show.sections(), layout);
        }

        public JsonNode poster() {
            return show.layout().get("poster");
        }
    }

    private final List<VenueTemplate> templates;

    public VenueTemplates(JsonMapper json) {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:demo/venues/*.json");
            this.templates = Arrays.stream(files)
                    .map(file -> read(json, file))
                    .sorted(Comparator.comparing(VenueTemplate::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<VenueTemplate> all() {
        return templates;
    }

    public Optional<VenueTemplate> find(String id) {
        return templates.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private static VenueTemplate read(JsonMapper json, Resource file) {
        try (InputStream in = file.getInputStream()) {
            return json.readValue(in, VenueTemplate.class);
        } catch (IOException e) {
            throw new UncheckedIOException("bad venue template " + file.getFilename(), e);
        }
    }
}

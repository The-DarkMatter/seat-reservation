package dev.amogh.seats.show;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    /** Admin only (enforced in SecurityConfig). */
    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowView create(@RequestBody CreateShowRequest request) {
        return shows.create(request);
    }

    /** Public listing; featured (the demo events) unless ?kind=api. */
    @GetMapping("/shows")
    public List<ShowSummary> list(@RequestParam(defaultValue = "featured") String kind,
                                  @RequestParam(defaultValue = "20") int limit) {
        return shows.list(kind, limit);
    }

    /** Compact live seat states for polling UIs (cached for 500 ms). */
    @GetMapping("/shows/{showId}/seatmap")
    public SeatMap seatMap(@PathVariable String showId) {
        return shows.seatMap(showId);
    }

    @GetMapping("/shows/{showId}")
    public ShowView get(@PathVariable String showId) {
        return shows.get(showId);
    }
}

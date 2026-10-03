package dev.amogh.seats.show;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @GetMapping("/shows/{showId}")
    public ShowView get(@PathVariable String showId) {
        return shows.get(showId);
    }
}

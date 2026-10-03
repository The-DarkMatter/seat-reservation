package dev.amogh.seats.web;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Kursi UI (a single-page app built into classpath:/static). A
 * browser asking for "/" (Accept: text/html) gets the app; curl and API clients
 * still get the JSON index from {@link IndexController}. The app's own routes
 * are forwarded to index.html so deep links and refreshes work.
 */
@Controller
public class SpaController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return "forward:/index.html";
    }

    @GetMapping({"/events/{id}", "/checkout/{id}", "/tickets/{id}", "/me", "/lab"})
    public String app() {
        return "forward:/index.html";
    }
}

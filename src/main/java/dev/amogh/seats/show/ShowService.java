package dev.amogh.seats.show;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.amogh.seats.web.ApiException;

@Service
public class ShowService {

    static final int DEFAULT_PER_USER_LIMIT = 4;
    static final int MAX_SEATS = 100_000;
    static final long MAX_PRICE_PAISE = 10_000_000_00L; // ₹1 crore per seat is plenty
    static final int MAX_HOLD_TTL_SECONDS = 3600;
    static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private final ShowRepository repository;
    private final ShowCatalog catalog;

    public ShowService(ShowRepository repository, ShowCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    @Transactional
    public ShowView create(CreateShowRequest req) {
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 200) {
            throw ApiException.badRequest("invalid_name", "name is required (1-200 characters)");
        }
        List<String> labels = validateLabels(req.seats());
        if (req.pricePaise() == null || req.pricePaise() < 0 || req.pricePaise() > MAX_PRICE_PAISE) {
            throw ApiException.badRequest("invalid_price",
                    "price_paise is required: an integer number of paise between 0 and " + MAX_PRICE_PAISE);
        }
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("invalid_per_user_limit", "per_user_limit must be between 1 and 100");
        }
        Integer ttl = req.holdTtlSeconds();
        if (ttl != null && (ttl < 1 || ttl > MAX_HOLD_TTL_SECONDS)) {
            throw ApiException.badRequest("invalid_hold_ttl",
                    "hold_ttl_seconds, if set, must be between 1 and " + MAX_HOLD_TTL_SECONDS);
        }

        var show = new Show(ShowIds.newId(), name, req.pricePaise(), limit, ttl, labels.size(),
                new LinkedHashSet<>(labels));
        repository.insertShow(show);
        repository.insertSeats(show.id(), labels);
        catalog.remember(show);

        var seats = labels.stream().map(l -> new ShowView.SeatView(l, "available")).toList();
        return toView(show, seats);
    }

    public ShowView get(String showId) {
        Show show = catalog.require(showId);
        return toView(show, repository.findSeatStates(show.id()));
    }

    private static ShowView toView(Show show, List<ShowView.SeatView> seats) {
        int available = 0, held = 0, confirmed = 0;
        for (var seat : seats) {
            switch (seat.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status " + seat.status());
            }
        }
        return new ShowView(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.holdTtlSeconds(), show.totalSeats(), new ShowView.Counts(available, held, confirmed), seats);
    }

    private static List<String> validateLabels(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw ApiException.badRequest("invalid_seats", "seats must be a non-empty array of seat labels");
        }
        if (seats.size() > MAX_SEATS) {
            throw ApiException.badRequest("invalid_seats", "a show can have at most " + MAX_SEATS + " seats");
        }
        var seen = new HashSet<String>(seats.size() * 2);
        for (String label : seats) {
            if (label == null || !SEAT_LABEL.matcher(label).matches()) {
                throw ApiException.badRequest("invalid_seats",
                        "seat labels must be 1-32 characters of A-Z a-z 0-9 . _ -",
                        Map.of("seat", String.valueOf(label)));
            }
            if (!seen.add(label)) {
                throw ApiException.badRequest("duplicate_seats", "seat labels must be unique",
                        Map.of("seat", label));
            }
        }
        return List.copyOf(seats);
    }
}

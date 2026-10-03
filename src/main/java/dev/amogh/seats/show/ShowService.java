package dev.amogh.seats.show;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.amogh.seats.show.CreateShowRequest.RowSpec;
import dev.amogh.seats.show.CreateShowRequest.SectionSpec;
import dev.amogh.seats.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ShowService {

    static final int DEFAULT_PER_USER_LIMIT = 4;
    static final int MAX_SEATS = 100_000;
    static final long MAX_PRICE_PAISE = 10_000_000_00L; // ₹1 crore per seat is plenty
    static final int MAX_HOLD_TTL_SECONDS = 3600;
    static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    static final int MAX_SECTIONS = 20;
    static final int MAX_ROWS_PER_SECTION = 100;
    static final int MAX_SEATS_PER_ROW = 200;
    static final int MAX_STANDING_CAPACITY = 50_000;
    static final int MAX_LAYOUT_JSON = 16_384;
    static final Pattern SECTION_CODE = Pattern.compile("[A-Z0-9]{1,12}");
    static final Pattern ROW_NAME = Pattern.compile("[A-Z]{1,3}");

    static final int MAX_LIST = 50;

    private final ShowRepository repository;
    private final ShowCatalog catalog;
    private final JsonMapper json;

    /**
     * Seat maps are polled by every open browser. Caching each one for half a
     * second means N viewers cost about two DB reads per second, not N. Caffeine
     * computes a missing entry once while concurrent callers wait for it, so a
     * crowd arriving at once doesn't stampede MySQL either.
     */
    private final Cache<String, SeatMap> seatMaps = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(500))
            .maximumSize(1000)
            .build();
    private final Cache<String, List<ShowSummary>> listings = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(1))
            .maximumSize(20)
            .build();

    public ShowService(ShowRepository repository, ShowCatalog catalog, JsonMapper json) {
        this.repository = repository;
        this.catalog = catalog;
        this.json = json;
    }

    /** POST /shows. */
    @Transactional
    public ShowView create(CreateShowRequest req) {
        return create(req, "api");
    }

    /** Also used by the demo module, which creates "featured" and "demo" shows. */
    @Transactional
    public ShowView create(CreateShowRequest req, String kind) {
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 200) {
            throw ApiException.badRequest("invalid_name", "name is required (1-200 characters)");
        }
        String venue = req.venue() == null || req.venue().isBlank() ? null : req.venue().strip();
        if (venue != null && venue.length() > 200) {
            throw ApiException.badRequest("invalid_venue", "venue must be at most 200 characters");
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
        JsonNode layout = checkLayoutJson(req.layout(), "layout");

        Plan plan = req.sections() != null ? planSections(req) : planFlat(req);
        long fromPrice = plan.sections().stream().mapToLong(Section::pricePaise).min().orElseThrow();

        var seatSections = new LinkedHashMap<String, String>();
        plan.seats().forEach(s -> seatSections.put(s.label(), s.section()));
        var show = new Show(ShowIds.newId(), name, fromPrice, limit, ttl, plan.seats().size(), venue,
                req.startsAt(), kind, layout, plan.sections(), seatSections);
        repository.insertShow(show);
        repository.insertSections(show.id(), plan.sections());
        repository.insertSeats(show.id(), plan.seats());
        catalog.remember(show);

        var seats = plan.seats().stream().map(s -> new ShowView.SeatView(s.label(), "available")).toList();
        return toView(show, seats);
    }

    public ShowView get(String showId) {
        Show show = catalog.require(showId);
        return toView(show, repository.findSeatStates(show.id()));
    }

    /** GET /shows: the newest visible shows of a kind (featured events by default). */
    public List<ShowSummary> list(String kind, int limit) {
        if (!kind.equals("featured") && !kind.equals("api")) {
            throw ApiException.badRequest("invalid_kind", "kind must be featured or api");
        }
        if (limit < 1 || limit > MAX_LIST) {
            throw ApiException.badRequest("invalid_limit", "limit must be between 1 and " + MAX_LIST);
        }
        return listings.get(kind + ":" + limit, k -> loadList(kind, limit));
    }

    private List<ShowSummary> loadList(String kind, int limit) {
        var countsByShow = new LinkedHashMap<String, Map<String, int[]>>();
        for (var row : repository.countRecentShows(kind, limit)) {
            countsByShow.computeIfAbsent(row.showId(), k -> new LinkedHashMap<>())
                    .put(row.section(), new int[] {row.available(), row.held(), row.confirmed()});
        }
        var out = new ArrayList<ShowSummary>();
        countsByShow.forEach((id, counts) -> {
            Show show = catalog.require(id);
            int[] all = new int[3];
            var sections = new ArrayList<ShowView.SectionView>();
            for (Section s : show.sections()) {
                int[] c = counts.getOrDefault(s.code(), new int[3]);
                for (int i = 0; i < 3; i++) {
                    all[i] += c[i];
                }
                sections.add(new ShowView.SectionView(s.code(), s.name(), s.pricePaise(), s.standing(), s.capacity(),
                        ShowView.Counts.of(c), null, s.display()));
            }
            out.add(new ShowSummary(show.id(), show.name(), show.venue(), show.startsAt(), show.pricePaise(),
                    show.holdTtlSeconds(), show.totalSeats(), ShowView.Counts.of(all), sections, show.layout()));
        });
        return List.copyOf(out);
    }

    /** GET /shows/{id}/seatmap, at most half a second old. */
    public SeatMap seatMap(String showId) {
        Show show = catalog.require(showId);
        return seatMaps.get(show.id(), id -> loadSeatMap(show));
    }

    private SeatMap loadSeatMap(Show show) {
        var states = repository.findSeatStateCodes(show.id());
        int[] all = new int[3];
        var sections = new ArrayList<SeatMap.SectionState>();
        for (Section s : show.sections()) {
            CharSequence codes = states.getOrDefault(s.code(), new StringBuilder());
            int[] c = new int[3];
            for (int i = 0; i < codes.length(); i++) {
                c[switch (codes.charAt(i)) {
                    case 'a' -> 0;
                    case 'h' -> 1;
                    default -> 2;
                }]++;
            }
            for (int i = 0; i < 3; i++) {
                all[i] += c[i];
            }
            sections.add(new SeatMap.SectionState(s.code(), ShowView.Counts.of(c),
                    s.standing() ? null : codes.toString()));
        }
        return new SeatMap(show.id(), ShowView.Counts.of(all), List.copyOf(sections));
    }

    static ShowView toView(Show show, List<ShowView.SeatView> seats) {
        var bySection = new LinkedHashMap<String, int[]>();
        show.sections().forEach(s -> bySection.put(s.code(), new int[3]));
        int[] all = new int[3];
        for (var seat : seats) {
            int slot = switch (seat.status()) {
                case "available" -> 0;
                case "held" -> 1;
                case "confirmed" -> 2;
                default -> throw new IllegalStateException("unknown seat status " + seat.status());
            };
            all[slot]++;
            bySection.get(show.seatSections().get(seat.seat()))[slot]++;
        }
        var sections = show.sections().stream()
                .map(s -> new ShowView.SectionView(s.code(), s.name(), s.pricePaise(), s.standing(), s.capacity(),
                        ShowView.Counts.of(bySection.get(s.code())), s.rows(), s.display()))
                .toList();
        return new ShowView(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.holdTtlSeconds(),
                show.totalSeats(), show.venue(), show.startsAt(), ShowView.Counts.of(all), sections, show.layout(),
                seats);
    }

    // ---- planning: turn either request shape into sections + seat rows ---------

    private record Plan(List<Section> sections, List<ShowRepository.SeatRow> seats) {
    }

    /** The original shape: a flat list of labels at one price, as one GENERAL section. */
    private static Plan planFlat(CreateShowRequest req) {
        List<String> labels = validateLabels(req.seats());
        checkPrice(req.pricePaise(), "price_paise");
        var section = new Section(Section.GENERAL, "General", req.pricePaise(), false, 0, labels.size(), null, null);
        var seats = labels.stream().map(l -> new ShowRepository.SeatRow(l, Section.GENERAL)).toList();
        return new Plan(List.of(section), seats);
    }

    /**
     * Seated sections get labels {@code CODE-ROWn} (GOLD-X18); standing ones get
     * {@code CODE-nnn} (SILVER-042), which nobody picks by name.
     */
    private Plan planSections(CreateShowRequest req) {
        if (req.seats() != null) {
            throw ApiException.badRequest("invalid_seats", "send either seats or sections, not both");
        }
        if (req.pricePaise() != null) {
            throw ApiException.badRequest("invalid_price",
                    "with sections, the price is set per section (sections[].price_paise)");
        }
        if (req.sections().isEmpty() || req.sections().size() > MAX_SECTIONS) {
            throw ApiException.badRequest("invalid_sections", "sections must have 1-" + MAX_SECTIONS + " entries");
        }
        var sections = new ArrayList<Section>();
        var seats = new ArrayList<ShowRepository.SeatRow>();
        var codes = new HashSet<String>();
        for (SectionSpec spec : req.sections()) {
            if (spec == null || spec.code() == null || !SECTION_CODE.matcher(spec.code()).matches()) {
                throw ApiException.badRequest("invalid_sections", "section code must be 1-12 characters of A-Z 0-9");
            }
            String code = spec.code();
            if (!codes.add(code)) {
                throw ApiException.badRequest("invalid_sections", "section codes must be unique", Map.of("section", code));
            }
            String name = spec.name() == null ? "" : spec.name().strip();
            if (name.isEmpty() || name.length() > 100) {
                throw ApiException.badRequest("invalid_sections", "section name is required (1-100 characters)",
                        Map.of("section", code));
            }
            checkPrice(spec.pricePaise(), "sections[].price_paise");
            JsonNode display = checkLayoutJson(spec.display(), "sections[].display");

            int before = seats.size();
            JsonNode rows = null;
            boolean standing = Boolean.TRUE.equals(spec.standing());
            if (standing) {
                if (spec.rows() != null) {
                    throw ApiException.badRequest("invalid_sections", "a standing section has a capacity, not rows",
                            Map.of("section", code));
                }
                Integer capacity = spec.capacity();
                if (capacity == null || capacity < 1 || capacity > MAX_STANDING_CAPACITY) {
                    throw ApiException.badRequest("invalid_sections",
                            "a standing section needs a capacity of 1-" + MAX_STANDING_CAPACITY, Map.of("section", code));
                }
                int width = Math.max(3, String.valueOf(capacity).length());
                for (int i = 1; i <= capacity; i++) {
                    seats.add(new ShowRepository.SeatRow(code + "-" + zeroPad(i, width), code));
                }
            } else {
                rows = json.valueToTree(planRows(code, spec.rows(), seats));
            }
            sections.add(new Section(code, name, spec.pricePaise(), standing, sections.size(), seats.size() - before,
                    rows, display));
            if (seats.size() > MAX_SEATS) {
                throw ApiException.badRequest("invalid_seats", "a show can have at most " + MAX_SEATS + " seats");
            }
        }
        return new Plan(List.copyOf(sections), List.copyOf(seats));
    }

    private static List<RowSpec> planRows(String code, List<RowSpec> rows, List<ShowRepository.SeatRow> out) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS_PER_SECTION) {
            throw ApiException.badRequest("invalid_sections",
                    "a seated section needs 1-" + MAX_ROWS_PER_SECTION + " rows", Map.of("section", code));
        }
        var names = new HashSet<String>();
        var cleaned = new ArrayList<RowSpec>();
        for (RowSpec row : rows) {
            if (row == null || row.row() == null || !ROW_NAME.matcher(row.row()).matches() || !names.add(row.row())) {
                throw ApiException.badRequest("invalid_sections", "row names must be unique, 1-3 letters A-Z",
                        Map.of("section", code));
            }
            if (row.seats() == null || row.seats() < 1 || row.seats() > MAX_SEATS_PER_ROW) {
                throw ApiException.badRequest("invalid_sections",
                        "each row needs 1-" + MAX_SEATS_PER_ROW + " seats", Map.of("section", code, "row", row.row()));
            }
            var aisles = row.aislesAfter() == null ? List.<Integer>of() : row.aislesAfter();
            if (aisles.size() > 20 || aisles.stream().anyMatch(a -> a == null || a < 1 || a >= row.seats())) {
                throw ApiException.badRequest("invalid_sections",
                        "aisles_after must be seat numbers inside the row", Map.of("section", code, "row", row.row()));
            }
            for (int n = 1; n <= row.seats(); n++) {
                out.add(new ShowRepository.SeatRow(code + "-" + row.row() + n, code));
            }
            cleaned.add(new RowSpec(row.row(), row.seats(), aisles.stream().sorted().distinct().toList()));
        }
        return cleaned;
    }

    private static String zeroPad(int n, int width) {
        String s = Integer.toString(n);
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }

    private static void checkPrice(Long price, String field) {
        if (price == null || price < 0 || price > MAX_PRICE_PAISE) {
            throw ApiException.badRequest("invalid_price",
                    field + " is required: an integer number of paise between 0 and " + MAX_PRICE_PAISE);
        }
    }

    /** Layout is stored and echoed back for the UI, so only its size is checked. */
    private JsonNode checkLayoutJson(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || json.writeValueAsString(node).length() > MAX_LAYOUT_JSON) {
            throw ApiException.badRequest("invalid_layout",
                    field + " must be a JSON object of at most " + MAX_LAYOUT_JSON + " characters");
        }
        return node;
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

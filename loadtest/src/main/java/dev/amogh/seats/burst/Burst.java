package dev.amogh.seats.burst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.amogh.seats.burst.Http.Res;

/**
 * Reproduces an on-sale stampede against a running service and checks the
 * correctness bar from the outside, using only the public API and /metrics.
 *
 * <pre>
 *   ./burst.sh https://seats.amogh.cloud            (or: make burst BASE_URL=...)
 *   ./burst.sh http://localhost:8080 --stampede 30000 --concurrency 2000
 * </pre>
 *
 * Phases: hot-seat storm (many users, same seat), on-sale stampede (skewed
 * seat demand, multi-seat requests, exact retries, same-key-different-seats),
 * per-user-limit flood, identity spoofing, cancel + re-book. A poller checks
 * available + held + confirmed == total throughout. Exits non-zero on any FAIL.
 */
public final class Burst {

    record Config(String baseUrl, int hotSeats, int buyersPerHotSeat, int stampede, int rows, int seatsPerRow,
                  int concurrency, String adminSecret, boolean checkMetrics) {
    }

    record Attempt(String phase, String user, String key, List<String> seats, Res res) {
        boolean created() {
            return res.status() == 201 && !res.replayed();
        }
    }

    record Check(String name, boolean pass, String detail) {
    }

    record Reservation(String id, String show, String user, List<String> seats) {
    }

    private final Config cfg;
    private final Http http;
    private final String run = UUID.randomUUID().toString().substring(0, 6);
    private final Random random = new Random();
    private final List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private int cancelsThatChangedState;
    private int otherRequests5xx;

    private Burst(Config cfg) {
        this.cfg = cfg;
        this.http = new Http(cfg.baseUrl(), cfg.concurrency());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].startsWith("-")) {
            System.err.println("usage: burst <BASE_URL> [--stampede N] [--hot-seats N] [--buyers-per-hot-seat N]"
                    + " [--rows N] [--seats-per-row N] [--concurrency N] [--admin-secret S] [--no-metrics-check]");
            System.exit(2);
        }
        var opts = new HashMap<String, String>();
        boolean checkMetrics = true;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--no-metrics-check")) {
                checkMetrics = false;
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                opts.put(args[i].substring(2), args[++i]);
            }
        }
        var cfg = new Config(args[0],
                Integer.parseInt(opts.getOrDefault("hot-seats", "5")),
                Integer.parseInt(opts.getOrDefault("buyers-per-hot-seat", "500")),
                Integer.parseInt(opts.getOrDefault("stampede", "15000")),
                Integer.parseInt(opts.getOrDefault("rows", "20")),
                Integer.parseInt(opts.getOrDefault("seats-per-row", "100")),
                Integer.parseInt(opts.getOrDefault("concurrency", "1000")),
                opts.getOrDefault("admin-secret", System.getenv().getOrDefault("ADMIN_SECRET", "demo-admin-secret")),
                checkMetrics);
        boolean ok = new Burst(cfg).run();
        System.exit(ok ? 0 : 1);
    }

    // ---------------------------------------------------------------------------

    private boolean run() throws Exception {
        System.out.printf("%n=== Seat reservation burst against %s (run %s) ===%n", http.base(), run);

        var ready = http.get("/health/ready", null);
        if (ready.status() != 200) {
            System.out.printf("FAIL  service not ready: GET /health/ready -> %d %s%n", ready.status(), ready.raw());
            return false;
        }

        String admin = mintToken("admin-" + run, true);
        var labels = new ArrayList<String>();
        for (int r = 0; r < cfg.rows(); r++) {
            for (int s = 1; s <= cfg.seatsPerRow(); s++) {
                labels.add(rowName(r) + s);
            }
        }
        String show = createShow(admin, "on-sale-" + run, labels, 4);
        System.out.printf("show %s: %d seats, per_user_limit 4%n", show, labels.size());

        Map<String, Double> before = cfg.checkMetrics() ? scrape() : Map.of();

        var stop = new AtomicBoolean();
        var polls = new AtomicInteger();
        var drift = Collections.synchronizedList(new ArrayList<String>());
        Thread poller = Thread.ofVirtual().start(() -> pollInvariant(show, labels.size(), stop, polls, drift));

        hotSeatStorm(show, labels);
        stampede(show, labels);
        perUserLimitFlood(admin);
        identity(admin);
        cancelAndRebook(show);

        stop.set(true);
        poller.join();
        check("reconciliation held during the burst (" + polls.get() + " polls of GET /shows/{id})",
                drift.isEmpty() && polls.get() > 0, drift.isEmpty() ? "" : drift.subList(0, Math.min(3, drift.size())).toString());

        System.out.println("waiting 3s for seat gauges to refresh...");
        Thread.sleep(3000);
        finalReconciliation(show, labels.size(), before);
        return report();
    }

    // ---- phases ----------------------------------------------------------------

    /** 500 different users hit the same seat at once, for each of the first few seats. Some retry. */
    private void hotSeatStorm(String show, List<String> labels) throws Exception {
        var hot = labels.subList(0, cfg.hotSeats());
        var users = new ArrayList<String>();
        for (String seat : hot) {
            for (int i = 0; i < cfg.buyersPerHotSeat(); i++) {
                users.add("hot-" + run + "-" + seat + "-" + i);
            }
        }
        mintTokens(users);

        var tasks = new ArrayList<Callable<Attempt>>();
        for (String seat : hot) {
            for (int i = 0; i < cfg.buyersPerHotSeat(); i++) {
                String user = "hot-" + run + "-" + seat + "-" + i;
                String key = run + "-hot-" + seat + "-" + i;
                tasks.add(() -> reserve("hot-seat storm", show, user, List.of(seat), key));
                if (i % 20 == 0) { // ~5% of buyers double-click
                    tasks.add(() -> reserve("hot-seat storm", show, user, List.of(seat), key));
                }
            }
        }
        var results = timed("hot-seat storm", tasks);

        for (String seat : hot) {
            var forSeat = results.stream().filter(a -> a.seats().getFirst().equals(seat)).toList();
            var winners = forSeat.stream().filter(a -> a.res().status() == 201)
                    .map(a -> a.res().text("reservation_id")).distinct().count();
            long declined = forSeat.stream().filter(a -> a.res().status() == 409).count();
            long other = forSeat.size() - declined - forSeat.stream().filter(a -> a.res().status() == 201).count();
            check("hot seat " + seat + ": exactly one winner among " + forSeat.size() + " requests",
                    winners == 1 && other == 0,
                    "winners=" + winners + " declined(409)=" + declined + " other=" + other);
        }
    }

    /** Skewed demand: 70% of picks land on the best 15% of seats; 1-3 seats per request; retries mixed in. */
    private void stampede(String show, List<String> labels) throws Exception {
        int userCount = Math.max(1, cfg.stampede() / 3);
        var users = new ArrayList<String>();
        for (int i = 0; i < userCount; i++) {
            users.add("fan-" + run + "-" + i);
        }
        mintTokens(users);

        int good = Math.max(cfg.hotSeats() + 1, labels.size() * 15 / 100);
        record Req(String user, List<String> seats, String key) { }
        var base = new ArrayList<Req>();
        for (int i = 0; i < cfg.stampede(); i++) {
            int n = pick(new int[] {60, 30, 10}) + 1;
            var wanted = new ArrayList<String>();
            while (wanted.size() < n) {
                String seat = random.nextInt(100) < 70 ? labels.get(random.nextInt(good)) : labels.get(random.nextInt(labels.size()));
                if (!wanted.contains(seat)) {
                    wanted.add(seat);
                }
            }
            base.add(new Req(users.get(random.nextInt(userCount)), wanted, run + "-s-" + i));
        }
        var all = new ArrayList<>(base);
        var retried = new HashSet<String>();
        var reused = new HashMap<String, List<String>>();
        for (int i = 0; i < base.size(); i++) {
            var r = base.get(i);
            if (i % 10 == 0) { // exact retry: same user, seats and key
                all.add(r);
                retried.add(r.key());
            } else if (i % 50 == 1) { // same key, different seats: must be rejected
                var other = List.of(labels.get(labels.size() - 1 - random.nextInt(labels.size() / 2)));
                if (!other.equals(r.seats())) {
                    all.add(new Req(r.user(), other, r.key()));
                    reused.put(r.key(), other);
                }
            }
        }
        Collections.shuffle(all, random);

        var tasks = new ArrayList<Callable<Attempt>>();
        for (var r : all) {
            tasks.add(() -> reserve("stampede", show, r.user(), r.seats(), r.key()));
        }
        var results = timed("stampede", tasks);

        // Exact retries: every response for the key is the same outcome.
        var byKey = new HashMap<String, List<Attempt>>();
        for (var a : results) {
            byKey.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(a);
        }
        int inconsistent = 0;
        for (String key : retried) {
            var outcomes = byKey.get(key).stream()
                    .map(a -> a.res().status() + ":" + (a.res().status() == 201 ? a.res().text("reservation_id") : a.res().error()))
                    .distinct().count();
            if (outcomes != 1) {
                inconsistent++;
            }
        }
        check("exact retries are idempotent (" + retried.size() + " keys sent twice)", inconsistent == 0,
                inconsistent + " keys produced different outcomes");

        // Same key, different seats: exactly one body owns the key; the other gets idempotency_key_reuse.
        int violations = 0;
        for (var e : reused.entrySet()) {
            var forKey = byKey.get(e.getKey());
            long reuse = forKey.stream().filter(a -> "idempotency_key_reuse".equals(a.res().error())).count();
            long createdForKey = forKey.stream().filter(Attempt::created).count();
            if (reuse != 1 || createdForKey > 1) {
                violations++;
            }
        }
        check("same key + different seats -> 409 idempotency_key_reuse (" + reused.size() + " keys)",
                violations == 0, violations + " keys misbehaved");

        long weird = results.stream().filter(a -> a.res().status() != 201 && a.res().status() != 409).count();
        check("stampede: every response is 201 or a clean 409", weird == 0, weird + " other responses");
    }

    /** One user fires 10 parallel reserves on a fresh limit-4 show. */
    private void perUserLimitFlood(String admin) throws Exception {
        var seats = new ArrayList<String>();
        for (int i = 1; i <= 20; i++) {
            seats.add("L" + i);
        }
        String show = createShow(admin, "limit-" + run, seats, 4);
        String user = "greedy-" + run;
        mintTokens(List.of(user));
        var tasks = new ArrayList<Callable<Attempt>>();
        for (int i = 0; i < 10; i++) {
            String seat = seats.get(i);
            String key = run + "-greedy-" + i;
            tasks.add(() -> reserve("per-user limit", show, user, List.of(seat), key));
        }
        var results = timed("per-user limit", tasks);
        long won = results.stream().filter(a -> a.res().status() == 201).count();
        long limited = results.stream().filter(a -> "per_user_limit".equals(a.res().error())).count();
        int confirmed = http.get("/shows/" + show, null).body().get("counts").get("confirmed").asInt();
        check("per-user limit: 10 parallel reserves at limit 4 -> " + won + " won, " + limited + " per_user_limit",
                won == 4 && confirmed == 4 && limited == 6, "show confirmed=" + confirmed);
    }

    /** Body fields can't change who you are; you can't cancel someone else's reservation. */
    private void identity(String admin) {
        String show = createShow(admin, "identity-" + run, List.of("X1", "X2"), 4);
        String victim = "victim-" + run;
        String mallory = "mallory-" + run;
        mintTokens(List.of(victim, mallory));

        var victims = reserve("identity", show, victim, List.of("X1"), run + "-v");
        var spoof = http.post("/shows/" + show + "/reserve", tokens.get(mallory),
                Map.of("seats", List.of("X2"), "user_id", victim, "idempotency_key", run + "-m"));
        record(new Attempt("identity", mallory, run + "-m", List.of("X2"), spoof));
        check("spoofed user_id in body is ignored (acts as the token's user)",
                spoof.status() == 201 && mallory.equals(spoof.text("user_id")), "got user_id=" + spoof.text("user_id"));

        var steal = http.post("/reservations/" + victims.res().text("reservation_id") + "/cancel", tokens.get(mallory), Map.of());
        String x1 = seatStatus(show, "X1");
        check("cannot cancel someone else's reservation", steal.status() == 403 && "confirmed".equals(x1),
                "cancel -> " + steal.status() + ", seat X1 is " + x1);

        var anon = http.post("/shows/" + show + "/reserve", null, Map.of("seats", List.of("X2")));
        check("no token -> 401", anon.status() == 401, "got " + anon.status());
        countOther5xx(steal, anon);
    }

    /** Owners cancel while others try to grab the freed seats; a second cancel must change nothing. */
    private void cancelAndRebook(String show) throws Exception {
        var victims = reservations.values().stream()
                .filter(r -> r.show().equals(show) && !cancelled.contains(r.id()))
                .limit(20).toList();
        var rebookers = new ArrayList<String>();
        for (int i = 0; i < victims.size() * 5; i++) {
            rebookers.add("rebook-" + run + "-" + i);
        }
        mintTokens(rebookers);

        var tasks = new ArrayList<Callable<Attempt>>();
        for (var r : victims) {
            tasks.add(() -> cancel(r));
        }
        int i = 0;
        for (var r : victims) {
            for (int j = 0; j < 5; j++) {
                String user = rebookers.get(i++);
                String key = run + "-rb-" + user;
                tasks.add(() -> reserve("cancel + re-book", show, user, r.seats(), key));
            }
        }
        timed("cancel + re-book", tasks);

        int stillCancelled = 0;
        for (var r : victims) {
            var again = cancel(r);
            if (again.res().status() == 200 && "cancelled".equals(again.res().text("status"))) {
                stillCancelled++;
            }
        }
        check("cancelling again is a no-op (" + victims.size() + " reservations)", stillCancelled == victims.size(),
                stillCancelled + "/" + victims.size());
    }

    // ---- final checks ------------------------------------------------------------

    private void finalReconciliation(String show, int total, Map<String, Double> before) {
        var state = http.get("/shows/" + show, null).body();
        var counts = state.get("counts");
        int available = counts.get("available").asInt();
        int held = counts.get("held").asInt();
        int confirmed = counts.get("confirmed").asInt();
        check("final reconciliation: available " + available + " + held " + held + " + confirmed " + confirmed
                + " == total " + total, available + held + confirmed == total && state.get("total_seats").asInt() == total, "");

        // Every confirmed seat belongs to exactly one live reservation we were told about, and vice versa.
        var owner = new HashMap<String, String>();
        int doubleSold = 0;
        for (var r : reservations.values()) {
            if (!r.show().equals(show) || cancelled.contains(r.id())) {
                continue;
            }
            for (String seat : r.seats()) {
                if (owner.put(seat, r.id()) != null) {
                    doubleSold++;
                }
            }
        }
        check("no seat was ever confirmed to two live reservations", doubleSold == 0, doubleSold + " seats");
        var confirmedSeats = new HashSet<String>();
        for (var s : state.get("seats")) {
            if ("confirmed".equals(s.get("status").asString())) {
                confirmedSeats.add(s.get("seat").asString());
            }
        }
        check("confirmed seats in GET /shows/{id} == seats in the 201s we received (" + confirmedSeats.size() + ")",
                confirmedSeats.equals(owner.keySet()),
                "server-only=" + diff(confirmedSeats, owner.keySet()) + " client-only=" + diff(owner.keySet(), confirmedSeats));

        long fiveXX = attempts.stream().filter(a -> a.res().status() >= 500).count() + otherRequests5xx;
        long transport = attempts.stream().filter(a -> a.res().status() == 0).count();
        check("zero 5xx across the whole burst", fiveXX == 0, fiveXX + " responses");
        check("zero transport errors (timeouts / resets)", transport == 0, transport + " requests"
                + (transport > 0 ? " e.g. " + attempts.stream().filter(a -> a.res().status() == 0).findFirst().get().res().raw() : ""));

        if (!cfg.checkMetrics()) {
            return;
        }
        var after = scrape();
        long created = attempts.stream().filter(Attempt::created).count();
        long replays = attempts.stream().filter(a -> a.res().replayed()).count();
        expectDelta(before, after, "reservations_confirmed_total", "", created);
        expectDelta(before, after, "reservations_declined_total", "reason=\"seat_taken\"", countError("seat_taken"));
        expectDelta(before, after, "reservations_declined_total", "reason=\"per_user_limit\"", countError("per_user_limit"));
        expectDelta(before, after, "reservations_declined_total", "reason=\"idempotency_key_reuse\"", countError("idempotency_key_reuse"));
        expectDelta(before, after, "reservations_declined_total", "reason=\"idempotent_replay\"", replays);
        expectDelta(before, after, "reservations_cancelled_total", "", cancelsThatChangedState);

        String tag = "show_id=\"" + show + "\"";
        double gA = sum(after, "seats_available", tag), gH = sum(after, "seats_held", tag), gC = sum(after, "seats_confirmed", tag);
        check("gauges match GET /shows/{id}: seats_available " + (long) gA + ", seats_held " + (long) gH
                        + ", seats_confirmed " + (long) gC,
                gA == available && gH == held && gC == confirmed, "");
    }

    private void expectDelta(Map<String, Double> before, Map<String, Double> after, String name, String labels, long expected) {
        double delta = sum(after, name, labels) - sum(before, name, labels);
        check("metric " + name + (labels.isEmpty() ? "" : "{" + labels + "}") + " moved by " + (long) delta
                + " (observed " + expected + ")", (long) delta == expected, "");
    }

    private long countError(String code) {
        return attempts.stream().filter(a -> !a.res().replayed() && code.equals(a.res().error())).count();
    }

    // ---- report --------------------------------------------------------------------

    private boolean report() {
        var dist = new LinkedHashMap<String, Long>();
        dist.put("201 created", attempts.stream().filter(Attempt::created).count());
        dist.put("201 idempotent replay", attempts.stream().filter(a -> a.res().status() == 201 && a.res().replayed()).count());
        for (String code : List.of("seat_taken", "per_user_limit", "idempotency_key_reuse")) {
            dist.put("409 " + code, countError(code));
        }
        dist.put("409 idempotent replay", attempts.stream().filter(a -> a.res().status() == 409 && a.res().replayed()).count());
        dist.put("other 4xx", attempts.stream().filter(a -> a.res().status() >= 400 && a.res().status() < 500 && a.res().status() != 409).count());
        dist.put("5xx", attempts.stream().filter(a -> a.res().status() >= 500).count());
        dist.put("transport errors", attempts.stream().filter(a -> a.res().status() == 0).count());

        System.out.printf("%nOutcome distribution (%d reserve calls):%n", attempts.size());
        dist.forEach((k, v) -> System.out.printf("  %-26s %8d%n", k, v));

        var lat = attempts.stream().mapToLong(a -> a.res().nanos()).sorted().toArray();
        if (lat.length > 0) {
            System.out.printf("Reserve latency: p50 %dms  p95 %dms  p99 %dms  max %dms%n",
                    pct(lat, 50), pct(lat, 95), pct(lat, 99), lat[lat.length - 1] / 1_000_000);
        }

        System.out.println("\nChecks:");
        boolean ok = true;
        for (var c : checks) {
            ok &= c.pass();
            System.out.printf("  %s  %s%s%n", c.pass() ? "PASS" : "FAIL", c.name(),
                    c.pass() || c.detail().isEmpty() ? "" : "  [" + c.detail() + "]");
        }
        long passed = checks.stream().filter(Check::pass).count();
        System.out.printf("%nRESULT: %s (%d/%d checks passed)%n", ok ? "PASS" : "FAIL", passed, checks.size());
        return ok;
    }

    // ---- helpers -------------------------------------------------------------------

    private Attempt reserve(String phase, String show, String user, List<String> seats, String key) {
        var res = http.post("/shows/" + show + "/reserve", tokens.get(user),
                Map.of("seats", seats, "idempotency_key", key));
        var a = new Attempt(phase, user, key, seats, res);
        record(a);
        return a;
    }

    private void record(Attempt a) {
        attempts.add(a);
        if (a.res().status() == 201) {
            String id = a.res().text("reservation_id");
            var seats = new ArrayList<String>();
            a.res().body().get("seats").forEach(s -> seats.add(s.asString()));
            reservations.putIfAbsent(id, new Reservation(id, a.res().text("show_id"), a.res().text("user_id"), seats));
        }
    }

    private Attempt cancel(Reservation r) {
        var res = http.post("/reservations/" + r.id() + "/cancel", tokens.get(r.user()), Map.of());
        countOther5xx(res);
        if (res.status() == 200 && cancelled.add(r.id())) {
            synchronized (this) {
                cancelsThatChangedState++;
            }
        }
        return new Attempt("cancel", r.user(), null, r.seats(), res);
    }

    private synchronized void countOther5xx(Res... responses) {
        for (var r : responses) {
            if (r.status() >= 500) {
                otherRequests5xx++;
            }
        }
    }

    private List<Attempt> timed(String phase, List<Callable<Attempt>> tasks) throws Exception {
        long start = System.nanoTime();
        var results = fireAll(tasks);
        double secs = (System.nanoTime() - start) / 1e9;
        long fives = results.stream().filter(a -> a.res().status() >= 500).count();
        System.out.printf("  %-18s %6d requests in %5.2fs (%5.0f req/s), 5xx=%d%n", phase, results.size(), secs,
                results.size() / secs, fives);
        return results;
    }

    /** Starts every task at the same instant (latch), bounded only by the client's in-flight cap. */
    private static <T> List<T> fireAll(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<T>>(tasks.size());
            for (var t : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return t.call();
                }));
            }
            start.countDown();
            var out = new ArrayList<T>(tasks.size());
            for (var f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    private void pollInvariant(String show, int total, AtomicBoolean stop, AtomicInteger polls, List<String> drift) {
        while (!stop.get()) {
            var r = http.get("/shows/" + show, null);
            if (r.status() == 200) {
                var c = r.body().get("counts");
                int sum = c.get("available").asInt() + c.get("held").asInt() + c.get("confirmed").asInt();
                polls.incrementAndGet();
                if (sum != total) {
                    drift.add("sum=" + sum);
                }
            } else {
                countOther5xx(r);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String mintToken(String user, boolean admin) {
        var body = admin
                ? Map.of("user_id", user, "role", "admin", "admin_secret", cfg.adminSecret())
                : Map.of("user_id", user);
        var res = http.post("/auth/token", null, body);
        if (res.status() != 200) {
            throw new IllegalStateException("could not mint token for " + user + ": " + res.status() + " " + res.raw());
        }
        tokens.put(user, res.text("token"));
        return res.text("token");
    }

    private void mintTokens(List<String> users) {
        try {
            fireAll(users.stream().<Callable<String>>map(u -> () -> mintToken(u, false)).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String createShow(String admin, String name, List<String> seats, int limit) {
        var res = http.post("/shows", admin, Map.of("name", name, "seats", seats, "price_paise", 25000, "per_user_limit", limit));
        if (res.status() != 201) {
            throw new IllegalStateException("could not create show: " + res.status() + " " + res.raw());
        }
        return res.text("id");
    }

    private String seatStatus(String show, String seat) {
        for (var s : http.get("/shows/" + show, null).body().get("seats")) {
            if (seat.equals(s.get("seat").asString())) {
                return s.get("status").asString();
            }
        }
        return null;
    }

    private Map<String, Double> scrape() {
        var out = new HashMap<String, Double>();
        Matcher m = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*(?:\\{[^}]*})?) (\\S+)$", Pattern.MULTILINE)
                .matcher(http.getText("/metrics"));
        while (m.find()) {
            try {
                out.put(m.group(1), Double.parseDouble(m.group(2)));
            } catch (NumberFormatException ignored) {
                // skip NaN-like samples
            }
        }
        return out;
    }

    private static double sum(Map<String, Double> samples, String name, String labelFilter) {
        double total = 0;
        for (var e : samples.entrySet()) {
            String key = e.getKey();
            boolean nameMatches = key.equals(name) || key.startsWith(name + "{");
            if (nameMatches && key.contains(labelFilter)) {
                total += e.getValue();
            }
        }
        return total;
    }

    private void check(String name, boolean pass, String detail) {
        checks.add(new Check(name, pass, detail));
    }

    private int pick(int[] weights) {
        int r = random.nextInt(100);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (r < acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private static long pct(long[] sorted, int p) {
        int idx = Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, idx)] / 1_000_000;
    }

    private static String rowName(int r) {
        return r < 26 ? String.valueOf((char) ('A' + r)) : "R" + r;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        var d = new HashSet<>(a);
        d.removeAll(b);
        return d.size() > 5 ? Set.of(d.size() + " seats") : d;
    }
}

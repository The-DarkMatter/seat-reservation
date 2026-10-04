# Seat reservation at scale

A small service that sells assigned seats for a show and stays correct when tens of thousands of buyers hit it in the same second. It never sells a seat twice, never lets a user go over their limit, and never reserves twice for a retried request.

- **Live API:** https://seats.amogh.cloud (Oracle Cloud Ampere A1 VM)
- **Try it in a browser:** https://kursi.amogh.cloud. **Kursi** is a booking site on the same service. Open an event in two windows and click the same seat in both: exactly one of you gets it. Or send 1,500 bots at your own copy of a venue in the Rush lab. See [below](#kursi-the-booking-ui).
- **Live dashboard (public, no login):** [Grafana: on-sale burst](https://violetmonorail1413.grafana.net/public-dashboards/4a39fb254b98447aa608638245763b54) shows outcomes by reason, 5xx, reconciliation drift, seat gauges, latency, pool saturation, and logs searchable by request id
- **Raw metrics:** https://seats.amogh.cloud/metrics
- **Latest live burst:** run from GitHub's network against the live URL: 19,537 reserve calls, 26/26 checks passed, zero 5xx, zero transport errors ([job log](https://github.com/The-DarkMatter/seat-reservation/actions/runs/37150172050)). Re-run it any time: Actions → *live burst* → Run workflow.
- **Design write-up:** [WRITEUP.md](WRITEUP.md)

Java 25 · Spring Boot 4.1 · MySQL 8.4 (InnoDB, READ COMMITTED) · plain JDBC · Flyway · Micrometer/Prometheus · Docker Compose · Caddy · Grafana Alloy/Cloud · Preact + TypeScript (UI) · Playwright

The version built for the original brief is tagged [`v1-submission`](https://github.com/The-DarkMatter/seat-reservation/tree/v1-submission). Everything since is additive: the brief's API and its guarantees are unchanged, and the same burst passes.

## Run it

You only need Docker.

```bash
docker compose up -d --build          # MySQL + API + UI on http://localhost:8080
curl localhost:8080/health/ready      # {"status":"UP",...} once MySQL is reachable

docker compose --profile obs up -d    # adds Prometheus :9090 and Grafana :3000 (dashboard preloaded)
```

Run the tests with `./mvnw verify` (JDK 25 + Docker for Testcontainers). They include real races against MySQL: 500 buyers on one seat, parallel per-user-limit floods, 50-way same-key retries, opposite-order multi-seat requests, a 500-buyer standing-area storm, and holds racing to take the same seat.

UI: `cd web && npm ci && npm test` (unit), `npx playwright test` (end to end, against the compose stack), `npm run dev` (dev server on :5173, proxying the API).

## The one-command burst

```bash
./burst.sh https://seats.amogh.cloud          # or: make burst BASE_URL=https://seats.amogh.cloud
./burst.sh http://localhost:8080              # against the local compose stack
./burst.sh <url> --stampede 30000 --concurrency 2000
```

It creates a fresh 2,000-seat show and then runs these phases:
- **Hot-seat storm:** 500 users go for each of 5 seats at once, some double-clicking.
- **Stampede:** 15,000 requests. 70% aim at the best 15% of seats, each asks for 1–3 seats, and exact retries and same-key-different-seats requests are mixed in.
- **Per-user-limit flood:** one user fires 10 parallel requests at limit 4.
- **Identity spoofing:** a request claims to be another user, and tries to cancel their reservation.
- **Cancel vs. re-book:** owners cancel while other users race to grab the freed seats.

A poller checks `available + held + confirmed == total` throughout. At the end the script prints the outcome distribution (201 / 409 by reason / replays / 5xx / transport errors), latency percentiles, and a list of PASS/FAIL checks:
- exactly one winner per hot seat
- the seats `GET /shows/{id}` reports as confirmed match the 201s the client received
- zero 5xx
- every counter and gauge moved by exactly what the client observed

It exits non-zero if any check fails. It uses a local JDK 25 if you have one, otherwise Docker.

## API

Every response is JSON. Money is integer paise.

### Auth

Identity comes from the bearer token, never the request body. `POST /auth/token` stands in for a real identity provider, so load tests can mint as many users as they need.

```bash
# user token
curl -s localhost:8080/auth/token -H 'content-type: application/json' -d '{"user_id":"alice"}'
# admin token (needed to create shows). The demo admin secret is "demo-admin-secret".
curl -s localhost:8080/auth/token -H 'content-type: application/json' \
  -d '{"user_id":"ops","role":"admin","admin_secret":"demo-admin-secret"}'
```

### Endpoints

| Method & path | Who | What |
|---|---|---|
| `POST /shows` | admin | `{"name","seats":[...],"price_paise", "per_user_limit"?(4), "hold_ttl_seconds"?}` → 201 with every seat available. Or the sectioned shape: `"sections":[{"code","name","price_paise","rows":[{"row":"A","seats":24,"aisles_after":[6]}]}, {"code","name","price_paise","standing":true,"capacity":500}]` |
| `GET /shows/{id}` | anyone | per-seat status (`available`/`held`/`confirmed`) and `counts`, plus per-section counts; `available+held+confirmed == total_seats` |
| `GET /shows` | anyone | featured events (or `?kind=api`) with counts per section |
| `GET /shows/{id}/seatmap` | anyone | one character per seat (`a`/`h`/`c`) per section, for polling; cached 500 ms |
| `POST /shows/{id}/reserve` | user | `{"seats":["A12"],"idempotency_key":"..."}`, or send the key as an `Idempotency-Key` header. Standing areas: `{"section":"PIT","quantity":2}`. Add `"allow_partial":true` to take whatever is free |
| `POST /reservations/{id}/seats` | owner | adds seats to a live hold, on its existing timer; same body as reserve |
| `GET /reservations/{id}` | owner | the reservation |
| `GET /me/reservations` | user | your reservations, newest first (`?show_id=` to filter) |
| `POST /reservations/{id}/confirm` | owner | turns a hold into a sale (shows with `hold_ttl_seconds`); idempotent |
| `POST /reservations/{id}/cancel` | owner | frees the seats; idempotent |
| `GET /health/live`, `GET /health/ready` | anyone | liveness; readiness checks MySQL and fails closed (503) |
| `GET /metrics` | anyone | Prometheus |

### Reserve outcomes

| Status | Body `error` | Meaning |
|---|---|---|
| 201 | none | `{"reservation_id","show_id","user_id","seats","amount_paise","status":"confirmed"}` (`"held"` + `expires_at` on hold-mode shows) |
| 201/409 + `Idempotent-Replayed: true` | | a retry of the same key and request; the original response, byte for byte |
| 409 | `seat_taken` | someone else holds or bought one of the seats (lists which) |
| 409 | `per_user_limit` | you'd go over the show's per-user limit |
| 409 | `idempotency_key_reuse` | the key was already used for a different show or seat set |
| 409 | `section_sold_out` | a standing area doesn't have as many places left as you asked for |
| 400 | `unknown_seats` / `duplicate_seats` / `invalid_seats` / `malformed_request` | bad input |
| 401 / 403 / 404 | | no or invalid token / not yours / no such show or reservation |

**Multi-seat requests are all-or-nothing by default.** If any requested seat is taken, nothing is reserved. With `"allow_partial": true` you get the free ones, and the response carries a `shortfall` listing what you didn't get.

### A full walkthrough

```bash
B=http://localhost:8080
ADMIN=$(curl -s $B/auth/token -H 'content-type: application/json' -d '{"user_id":"ops","role":"admin","admin_secret":"demo-admin-secret"}' | jq -r .token)
SHOW=$(curl -s $B/shows -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A12","A13"],"price_paise":25000}' | jq -r .id)
ALICE=$(curl -s $B/auth/token -H 'content-type: application/json' -d '{"user_id":"alice"}' | jq -r .token)

curl -s $B/shows/$SHOW/reserve -H "authorization: Bearer $ALICE" -H 'content-type: application/json' \
  -H 'Idempotency-Key: 7f3c' -d '{"seats":["A12","A13"]}'
curl -s $B/shows/$SHOW | jq .counts        # {"available":2,"held":0,"confirmed":2}
```

## Kursi: the booking UI

https://kursi.amogh.cloud is a booking site on top of this service, served by the same app. Browsers get the UI at `/`, while curl still gets the JSON index.
- **Booking:**
  - Pick an event, then a priced section on the venue map.
  - Zoom into the seats with Ctrl+scroll or pinch; hover for row, seat and price.
  - Book seats, or a quantity in a standing area.
  - Pay on a mock checkout that confirms the hold. Its countdown runs on the server's clock, and there's a "simulate a failed payment" switch.
- **Live:** seat states refresh every second, so seats taken by others turn grey as you watch. If someone beats you to a seat, you're told immediately.
- **Two buyers in one browser:** each tab is its own guest buyer. "Open 2nd buyer" lets you race yourself.
- **Rush lab:** make your own copy of a venue and send up to 1,500 bots at it through the real reserve path. Watch `available + held + confirmed = total` hold the whole time; the spike also shows on the public dashboard.

The code is in `web/` (Preact + TypeScript, no CDN, about 27 KB gzipped). The Dockerfile builds it into the jar. The demo endpoints (`/demo/**`) are the only rate-limited ones, per IP; the reservation API itself is never throttled. Visitors' demo shows are deleted after 24 h. Design notes are in [WRITEUP §8](WRITEUP.md#8-beyond-the-brief-kursi-a-booking-ui-on-the-same-service).

## Observability

| What | Where |
|---|---|
| Business counters | `reservations_confirmed_total`, `reservations_declined_total{reason=seat_taken\|per_user_limit\|idempotent_replay\|idempotency_key_reuse}`, `reservations_held_total`, `reservations_cancelled_total`, `reservations_expired_total`, `seat_sales_total`, `seat_releases_total{cause}`, `reservation_lock_retries_total` |
| Seat state (read from MySQL, so it reconciles with the API) | `seats_available`, `seats_held`, `seats_confirmed`, `seats_capacity` `{show_id}` |
| Latency / saturation | `reservation_reserve_seconds` histogram by outcome, `http_server_requests_seconds`, `hikaricp_connections_{active,pending}` |
| Logs | ECS JSON on stdout. Every request has an `X-Request-Id` (yours if you send one), carried on every log line, plus one structured outcome line per reserve/cancel/confirm/expiry. In production they ship to Grafana Cloud Loki. |
| Dashboard | `ops/grafana/dashboards/seat-reservation.json`: preloaded in local Grafana (with data source and show pickers). The public Grafana Cloud dashboard uses `ops/grafana/seat-reservation-public.json`, a variable-free variant, because public dashboards don't support template variables |

## Repository map

```
src/main/java/dev/amogh/seats/
  reservation/   ReservationService (the transaction), ReservationRepository (all seat-moving SQL),
                 IdempotencyRepository, HoldSweeper
  show/          shows, sections, the immutable show catalog, listing and seat map
  demo/          Kursi demo: venue templates, featured events, rush bots, rate limits, cleanup
  auth/          JWT resource server, token minting
  obs/           business metrics, DB-derived seat gauges, readiness check
  web/           request ids + access log, JSON error handling, serving the UI
src/main/resources/db/migration/   schema (Flyway)
src/test/                          Testcontainers suites, including the concurrency races
web/                               the Kursi UI (Preact + TypeScript) and its Playwright tests
loadtest/                          the burst client
deploy/                            production compose override, Caddyfile, VM bootstrap, deploy-with-rollback
ops/                               Prometheus, Grafana dashboard, Alloy
.github/workflows/ci.yml           tests -> compose smoke burst -> UI end-to-end -> multi-arch image -> deploy -> live smoke burst
```

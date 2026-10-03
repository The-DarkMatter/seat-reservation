# Seat reservation at scale

A small service that sells assigned seats for a show and stays correct when tens of thousands of buyers hit it in the same second. It never sells a seat twice, never lets a user go over their limit, and never reserves twice for a retried request.

- **Live:** https://seats.amogh.cloud (Oracle Cloud Ampere A1 VM)
- **Metrics:** https://seats.amogh.cloud/metrics, plus a public Grafana dashboard: _link added after deploy_
- **Design write-up:** [WRITEUP.md](WRITEUP.md)

Java 25 · Spring Boot 4.1 · MySQL 8.4 (InnoDB, READ COMMITTED) · plain JDBC · Flyway · Micrometer/Prometheus · Docker Compose · Caddy · Grafana Alloy/Cloud

## Run it

You only need Docker.

```bash
docker compose up -d --build          # MySQL + API on http://localhost:8080
curl localhost:8080/health/ready      # {"status":"UP",...} once MySQL is reachable

docker compose --profile obs up -d    # adds Prometheus :9090 and Grafana :3000 (dashboard preloaded)
```

Run the tests with `./mvnw verify` (JDK 25 + Docker for Testcontainers). They include real races against MySQL: 500 buyers on one seat, parallel per-user-limit floods, 50-way same-key retries, and opposite-order multi-seat requests.

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
| `POST /shows` | admin | `{"name","seats":[...],"price_paise", "per_user_limit"?(4), "hold_ttl_seconds"?}` → 201 with every seat available |
| `GET /shows/{id}` | anyone | per-seat status (`available`/`held`/`confirmed`) and `counts`; `available+held+confirmed == total_seats` |
| `POST /shows/{id}/reserve` | user | `{"seats":["A12"],"idempotency_key":"..."}`, or send the key as an `Idempotency-Key` header |
| `GET /reservations/{id}` | owner | the reservation |
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
| 400 | `unknown_seats` / `duplicate_seats` / `invalid_seats` / `malformed_request` | bad input |
| 401 / 403 / 404 | | no or invalid token / not yours / no such show or reservation |

**Multi-seat requests are all-or-nothing.** If any requested seat is taken, nothing is reserved.

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

## Observability

| What | Where |
|---|---|
| Business counters | `reservations_confirmed_total`, `reservations_declined_total{reason=seat_taken\|per_user_limit\|idempotent_replay\|idempotency_key_reuse}`, `reservations_held_total`, `reservations_cancelled_total`, `reservations_expired_total`, `seat_sales_total`, `seat_releases_total{cause}`, `reservation_lock_retries_total` |
| Seat state (read from MySQL, so it reconciles with the API) | `seats_available`, `seats_held`, `seats_confirmed`, `seats_capacity` `{show_id}` |
| Latency / saturation | `reservation_reserve_seconds` histogram by outcome, `http_server_requests_seconds`, `hikaricp_connections_{active,pending}` |
| Logs | ECS JSON on stdout. Every request has an `X-Request-Id` (yours if you send one), carried on every log line, plus one structured outcome line per reserve/cancel/confirm/expiry. In production they ship to Grafana Cloud Loki. |
| Dashboard | `ops/grafana/dashboards/seat-reservation.json`: preloaded in local Grafana, and the same file is on Grafana Cloud |

## Repository map

```
src/main/java/dev/amogh/seats/
  reservation/   ReservationService (the transaction), ReservationRepository (all seat-moving SQL),
                 IdempotencyRepository, HoldSweeper
  show/          shows, the immutable show catalog
  auth/          JWT resource server, token minting
  obs/           business metrics, DB-derived seat gauges, readiness check
  web/           request ids + access log, JSON error handling
src/main/resources/db/migration/   schema (Flyway)
src/test/                          Testcontainers suites, including the concurrency races
loadtest/                          the burst client
deploy/                            production compose override, Caddyfile, VM bootstrap, deploy-with-rollback
ops/                               Prometheus, Grafana dashboard, Alloy
.github/workflows/ci.yml           tests -> compose smoke burst -> multi-arch image -> deploy -> live smoke burst
```

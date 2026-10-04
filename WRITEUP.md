# Write-up

## 1. The atomic decision

**Mechanism.** One row per seat holds that seat's state (`seats.status`, `reservation_id`, `user_id`, `held_until`). A seat is granted by a single conditional UPDATE whose WHERE clause *is* the business rule:

```sql
UPDATE seats SET status = ?, reservation_id = ?, user_id = ?, held_until = ?
WHERE show_id = ? AND label = ?
  AND (status = 'available' OR (status = 'held' AND held_until <= NOW(6)))
```

Exactly one row updated means you got the seat. Zero rows means it was taken, which returns a clean `409 seat_taken`.

**Why it's race-free.** InnoDB takes an exclusive lock on the row to update it and evaluates the WHERE clause against the latest committed version. If another transaction is mid-claim, this statement waits. When that transaction commits, it re-checks the condition against the new row, which now says `confirmed`, and matches nothing. 500 concurrent attempts on A12 therefore update exactly one row. There's no read-then-decide-then-write anywhere. Two schema guarantees back this up:
- one row per seat (PK `(show_id, label)`), so a seat can't have two owners;
- CHECK constraints tying `status` to `reservation_id` and `held_until`.

**Why not the alternatives:**
- **Read-then-write** double-sells.
- **A version column** would just be this same WHERE clause with extra steps.
- **`NOWAIT` / `SKIP LOCKED`** looks attractive for hot seats but is wrong here. If the transaction holding the lock then fails (say, its user is over the limit), everyone who skipped has already been told "taken", and the seat ends up with **zero** winners. The brief needs exactly one.

**Multi-seat and deadlocks.** A request claims its seats one UPDATE at a time **in sorted label order**, inside one transaction:
- **No deadlock:** two requests for {A12, A13} and {A13, A12} both lock A12 first, so neither can hold what the other is waiting for.
- **All-or-nothing:** the claims run after a SAVEPOINT. The first seat that's taken rolls back to it, releasing the earlier seats.

The global lock order is: idempotency row → per-user mutex row → seats ascending. Cancel, confirm and the expiry sweeper lock the reservation row, then its seats in label order. Nothing ever takes these in reverse, so there's no cycle. As a backstop, an InnoDB deadlock (1213) or lock-wait timeout (1205) retries the whole transaction, which is safe because the idempotency row rolls back with it. `reservation_lock_retries_total` has read 0 in every burst I've run.

**Per-user limit.** The transaction locks a `(show, user)` row with `SELECT … FOR UPDATE`, then counts that user's live seats directly from `seats`. Two parallel requests from one user serialize on that row, so they can't both pass the check. Counting from `seats` means there's no counter to drift when holds expire or get cancelled.

This needs **READ COMMITTED**: once we hold the mutex, a plain SELECT sees everything committed up to that moment. Under MySQL's default REPEATABLE READ the count could come from an older snapshot and miss seats a parallel request just committed.

**Partial requests** are all-or-nothing, as above.

### Found by load testing the real thing

- **Hot-seat throughput collapsed.** The hot-seat phase ran at 367 req/s against 2,400 for the general stampede. Sampling `performance_schema.data_lock_waits` showed nearly the whole connection pool queued on the five hot rows. I reproduced the cause with two `mysql` sessions: under READ COMMITTED, an UPDATE that had to **wait** for a row lock and then finds the row no longer matches **keeps** that lock until commit. (Without a wait, the lock on a non-matching row is released immediately.) So every loser held A12 through the rest of its transaction, and the storm went single file. Correctness was never affected, only throughput. (Reproduce it with `./ops/mysql/lock-retention-demo.sh`.)

  The fix is a non-locking read first: if a requested seat is visibly taken, decline without joining the lock queue. That read can only ever say "taken", and the grant still happens only in the conditional UPDATE, so a stale read can't double-sell. Results: hot-seat phase 7.2 s → 1.3 s, stampede 2.4k → 3.6k req/s, p99 3.1 s → 1.0 s.
- **MySQL had been ignoring its config file.** `my.cnf` was bind-mounted from a Windows checkout, so inside the container it looked world-writable, and MySQL silently skips world-writable config files. The server was running on defaults. The app was fine, because Hikari sets READ COMMITTED on every connection, but none of the server tuning applied. The settings are now `mysqld` flags in compose.

## 2. Idempotency

**Where the key lives:** table `idempotency_keys`, primary key `(user_id, idem_key)`, holding the request fingerprint, the HTTP status, and the exact response body. The fingerprint is sha256 of the show id plus the sorted seat list. Keys are scoped per user, so one user can't replay another user's key and read their reservation. The key can come as an `Idempotency-Key` header or an `idempotency_key` body field. Sending both with different values is a 400.

**How exactly-once is enforced.** The first statement of the reserve transaction is a plain `INSERT` of the key. If a concurrent request with the same key is still in flight, InnoDB makes this INSERT wait on the primary key. When that request commits, the INSERT fails with a duplicate-key error, and this request reads the committed row and **replays it byte for byte**. It returns the same status (201 or 409) and body, plus an `Idempotent-Replayed: true` header.

The outcome is written to the key row **in the same transaction** that produced it. "Reserved but key not recorded", or the reverse, can't happen. If the transaction rolls back, the key goes with it, and a retry starts fresh.

Two details:
- The response is stored as `TEXT`, not `JSON`, because MySQL's JSON type re-orders object keys.
- It's a plain `INSERT`, not `INSERT IGNORE`, which would also swallow unrelated errors.

**Declines are stored too.** A retry of a request that got `409 seat_taken` gets that same 409 back, even if the seat has since been released. A key means "this request, one answer", as with Stripe. Validation failures (400s) aren't stored; they never touched the database.

**Same key, different body** (another show or seat set): the fingerprint doesn't match, so the result is `409 idempotency_key_reuse` and nothing moves. Under concurrency, whichever body commits first owns the key, and every request with the other body gets the reuse error. The burst client checks this.

Confirm and cancel are idempotent by state rather than by key. Confirming a confirmed reservation, or cancelling a cancelled one, returns it unchanged. A doubled payment callback can't sell or charge twice.

## 3. Holds and expiry

Both models are built in:
- **Default:** reserve gives `confirmed` straight away, exactly as in the brief's example. The owner can `POST /reservations/{id}/cancel`.
- **Hold mode:** a show created with `hold_ttl_seconds` reserves as `held`, with `expires_at`. That's the payment window. `POST /reservations/{id}/confirm` turns the hold into a sale.

**Expiry is lazy, and computed on the database clock.** Every claim, per-user count, `GET /shows` read and gauge treats `status='held' AND held_until <= NOW(6)` as available. An overdue seat is free the instant it expires, with no timer involved in correctness. A sweeper runs every 2 s purely to tidy the rows. It works one reservation per transaction, uses `FOR UPDATE SKIP LOCKED` to step around holds that a confirm or cancel is touching, and takes locks in the same order as cancel.

**A release can never resurrect a seat sold to someone else.** Cancel and expiry free seats with `WHERE reservation_id = ?`. If Alice's hold expired and Bob bought the seat, the row now carries Bob's reservation id, and Alice's late cancel or sweep doesn't touch it.

**Confirm racing the expiry instant.** Confirm runs `UPDATE … WHERE reservation_id = ? AND status = 'held' AND held_until > NOW(6)`, and re-booking uses the claim above. Both hit the same row lock, so exactly one wins, and an expired hold returns `409 hold_expired`. Tests race this at the expiry boundary.

## 4. Consistency vs availability under a partition

This system chooses consistency. MySQL is the single system of record and makes every decision. The app instances are stateless: the in-memory show cache holds only immutable facts (price, limit, seat labels), never seat state.

- **App can't reach MySQL.** Readiness flips to 503 within about 1.5 s; it runs on its own one-connection pool, so a saturated pool during a burst doesn't make it flap. Reserves return `503 database_unavailable` with `Retry-After`. Nothing is sold from a cache or "accepted now, reconciled later". Selling seats we can't record is how you double-sell.
- **Clients can't reach us, or time out mid-request.** The idempotency key makes this safe. After the partition heals, a retry with the same key returns the true outcome of the original attempt instead of reserving again.
- **Adding replicas.** Asynchronous replication plus failover can lose a commit the old primary already acknowledged, and that seat can then be sold again on the new primary. I'd use semi-synchronous replication (`AFTER_SYNC`) or Group Replication in single-primary mode, so an acknowledged sale survives failover. On a network split, the minority side refuses writes. Reads like `GET /shows` could go to replicas, since stale is fine for display; the decision stays on the primary.

## 5. Observability: what pages me at 2am

The burst is observable live on the dashboard: outcome rate by reason, 5xx, reconciliation drift, seat gauges, p50/p95/p99, pool saturation, MySQL row-lock waits and host CPU. Logs are searchable by request id.

**Page:**
- The synthetic `/health/ready` check fails from two locations for 2 minutes. The service is down, or MySQL is unreachable and we're failing closed.
- **Any** 5xx on the reserve path for 2 minutes. By design, declines are 4xx, so a 5xx means a bug or an outage.
- **Reconciliation drift ≠ 0**, i.e. `seats_available + seats_held + seats_confirmed != seats_capacity`. This "can't happen", which is exactly why it pages.
- `hikaricp_connections_pending > 0` for 1 minute *and* reserve p99 > 1 s. The database is the bottleneck and users are waiting.

**Ticket, not page:**
- `reservation_lock_retries_total` rising. The lock-order assumption broke somewhere.
- Disk > 80%.
- TLS certificate < 14 days from expiry.
- The sweeper not running: expired holds piling up as `held` rows. Harmless for correctness, bad for counts.

Counters increment only after commit, so metrics never count something the database rolled back. Seat gauges are read **from MySQL** every second rather than tracked in memory. They reconcile with `GET /shows/{id}` and survive restarts. A per-show counter row would have been a hot spot serializing every reservation in the show.

## 6. AI usage: directed vs decided

I built this with Claude Code (Claude Opus 5.5) as a pair. It wrote nearly all of the code. I made the product and infrastructure decisions, tested the result, and sent it back when something was wrong.

**Decided by me** (Claude laid out options and trade-offs; the choice was mine):
- **Stack: Java 25 + Spring Boot 4.1.** I use Python a lot too, but the role is Java/Spring. My day job is Java 8, so I picked up records, sealed interfaces, pattern-matching switches and virtual threads as they came up in the code.
- **MySQL instead of Postgres,** because I've run MySQL in production. The cost was working around missing transaction-scoped advisory locks, `RETURNING` and `ON CONFLICT`, and choosing READ COMMITTED explicitly.
- **The hold model.** Confirm by default to match the brief's example, with opt-in TTL holds plus confirm, chosen after weighing the two release options the brief allows.
- **Hosting.** I researched the Oracle Cloud free tier, chose an always-on Ampere A1 VM, and upgraded the account to pay-as-you-go so Oracle won't reclaim the VM for being idle. I considered a "wake the server" button and dropped it: graders' scripts won't click a button, and a waking proxy would sit in the burst's path.
- **Cloudflare DNS-only** for `seats.amogh.cloud`, so a load test reaches the service without bot or DDoS filtering in front of it.
- **Grafana Cloud** for public metrics and logs (I use Grafana and Prometheus at work).
- **Building a real booking UI on top (Kursi, §8),** modelled on the seat-selection flow of District, which I researched and picked as the reference.
- **"All or nothing" vs "book what's left" as a buyer's choice** (`allow_partial`). The brief only asks for defined behaviour on partial requests, and all-or-nothing stays the default. While testing, I decided some buyers would rather take the seats that are left, so it became a per-request option.
- **One hold, one timer.** Testing the UI, I picked seats, went to pay, came back and picked more, and found I was being asked to pay for two separate holds on two separate timers. I decided seats added later should join the existing hold and its original countdown. That became `POST /reservations/{id}/seats`.

**Generated by Claude:**
- **The design proposals:** the conditional-UPDATE claim, sorted lock order, savepoint all-or-nothing, the idempotency key in the same transaction, the per-user mutex row, and `SKIP LOCKED` for standing areas.
- **The code:** the service, the Testcontainers concurrency tests, the burst client, Docker/compose, the deploy scripts, CI, the UI and its Playwright tests, and the first draft of these documents.

**Found by running the system:** both issues in §1 came from running the burst and investigating lock waits, as did two framework surprises:
- Spring Boot 4 turns off metrics export in tests.
- Prometheus reserves the `_total` suffix for counters.

**What I verified myself:**
- **The API, by hand, against the live service:** creating shows, reserving, retries with the same idempotency key, the per-user limit, holds, confirm and cancel.
- **The UI, end to end, on laptop and phone.** That testing found four problems, each now fixed and covered by a test:
  - Coming back to the seat map during a hold showed my held seats as already "yours", with no way to resume payment.
  - Seats picked later started a second hold with its own timer.
  - In the Rush lab, picking a different venue silently kept rushing the old one.
  - The public Grafana dashboard showed no data, because public dashboards don't support the template variables ours used.
- **The infrastructure setup** in Oracle Cloud, Cloudflare and Grafana Cloud, which I did myself following the deploy plan.

## 7. What I'd do next

- **Admission control for mega on-sales.** A virtual waiting room with a token bucket in Redis in front of reserve, so a 1M-user on-sale queues fairly instead of saturating the connection pool. Very hot shows could also be sharded by seat section.
- **Payments.** Holds plus confirm are the hook. I'd add a payment-intent step, with an outbox table so "confirmed" and "charge captured" can't diverge.
- **High availability.** Managed MySQL with semi-synchronous replicas and automatic failover (§4), plus two or more app replicas behind Caddy. That's safe today, because the database already arbitrates everything.
- **Housekeeping.** Idempotency keys are now cleaned up after 24 h, and the public demo endpoints are rate limited per IP. Still to do: rate limiting on the reservation API itself (deliberately off, so load tests measure the service) and an append-only audit log of seat transitions.
- **Faster cold starts** with a Java AOT cache or CRaC, and a real OIDC identity provider instead of the token stub.

## 8. Beyond the brief: Kursi, a booking UI on the same service

Everything above describes the service as the brief asked for it; the tag [`v1-submission`](https://github.com/The-DarkMatter/seat-reservation/tree/v1-submission) marks that version. Since then I built **Kursi** ("chair") on top: a booking site at **https://kursi.amogh.cloud**, served by the same app. The brief's API is unchanged and the same burst still passes against it. Everything new is additive:

- **Sections with their own prices.** Seated sections are rows of seats with aisles. Standing sections are booked by quantity (`{"section": "PIT", "quantity": 2}`). Shows created the original way become one `GENERAL` section.
- **Standing places use `FOR UPDATE SKIP LOCKED`.** It's the right tool there and the wrong one for named seats. Any free place will do, so skipping a place another transaction has locked loses nothing, and the query never waits on a row, so it can't deadlock. For a named seat, skipping could leave the seat with no winner at all if the transaction holding it rolls back. The mirror-image cost for standing: a place locked by a request that later rolls back reads as taken for that instant, so a nearly full section can report sold out a moment early.
- **`allow_partial`** books whatever is free and reports the shortfall (§6).
- **Adding seats to a live hold** (`POST /reservations/{id}/seats`) uses the same claim code. The new seats get the hold's existing expiry. Lock order is idempotency row, then the reservation row, then the user mutex, then seats. Reserve never takes a reservation-row lock, and cancel, confirm and the sweeper never take the user mutex, so this adds no lock cycle.
- **Read endpoints for the UI.** `GET /shows` lists events. `GET /shows/{id}/seatmap` returns one character per seat and is cached for 500 ms, so a crowd of viewers costs about two DB reads a second. `GET /me/reservations` lists your own bookings.
- **The demo.**
  - Featured events rotate themselves.
  - A visitor can get their own copy of a venue, and send up to 1,500 server-side bots at it through the real reserve path.
  - Guard rails: per-IP limits on the demo endpoints only, at most two rushes at a time, and cleanup after 24 h.
- **The UI.** Preact + TypeScript, served from the jar with no CDN. It has:
  - a live seat map with zoom;
  - a mock checkout over hold then confirm, with the countdown on the server's clock;
  - a Rush lab that shows `available + held + confirmed = total` while the bots run.

  Playwright tests in CI cover two browsers racing for one seat (exactly one wins), the checkout flow, standing places, and a rush.

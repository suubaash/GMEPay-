> 작업: rate-fx backlog 완성 / 출처: agent

# rate-fx build report — 2026-06-30 (branch agent/rate-fx)

## Build status
`./gradlew :services:rate-fx:test` — **GREEN**. 28 tests, 0 failures, 0 errors, 0 skipped
(12 new this run). Full-app `@SpringBootTest` context boots on H2 (no Redis).

## Tickets / gaps done this run
The highest-priority gaps from the brief and `7. PRD Use-Case Audit & Gap Plan` were the focus.
The engine itself (5-step RECEIVE, pool identity, derived BOK rates, quote TTL lifecycle, the
WP 4.5 same-currency short-circuit, IDENTITY 1.0) was already built in the frozen lib-rate; the
remaining service-level gaps were closed:

1. **WP 4.6 — PARTNER (Partner-B authoritative) quote source — was UNWIRED, now WIRED.**
   - `partnerb/PartnerBQuotePort` + `PartnerBQuote` value object; in-process
     `SnapshotPartnerBQuotePort` reads the latest `source='PARTNER'` row from `rate_snapshots`
     (config-gated by data, not a live endpoint — runs in tests/dev with no xe.com / Partner B).
   - `RateSource` enum (IDENTITY/LIVE/MANUAL/PARTNER); `PartnerRule` gains nullable
     `rateCollSource`/`ratePaySource` (null ⇒ LIVE), mapped by `RestConfigRegistryClient`.
   - `QuoteIssueService.buildRateInput` dispatches each leg by source — a PARTNER pay leg is priced
     by Partner B, not the treasury store.
   - **Deviation + unavailable handling:** `resolvePartnerCommitRate(...)` re-quotes at commit and
     throws `PARTNER_B_QUOTE_DEVIATION` (422) when drift > tolerance (default 1%); unavailable →
     `PARTNER_B_QUOTE_UNAVAILABLE` (503). No silent treasury fallback.
2. **Durable / restart-safe quote TTL behind the port, working WITHOUT Redis.**
   - `JpaQuoteTtlStore` over the `rate_quotes` audit table is now the default when
     `spring.data.redis.host` is unset (replaces the process-local in-memory default). Locks
     survive a restart for the remainder of their TTL; expiry governed by `expires_at`. Redis store
     still selected when a host is configured.
3. **Manual override endpoint + PARTNER-rate seeding.**
   - `POST /v1/rates/snapshots` (`RateSnapshotAdminController` + `RateSnapshotAdminService`) appends
     a new effective-dated MANUAL or PARTNER snapshot row (LIVE reserved for the XE feed; USD
     rejected as identity; non-positive rejected). This also seeds the in-process Partner B feed.

(XE scheduler was already config-gated `gmepay.rate-fx.xe.enabled` and persists LIVE snapshots —
no change needed; same-currency short-circuit WP 4.5 already in the frozen engine.)

## Completion estimate
Service-level rate-fx backlog: **~90%**. The money engine, quote lifecycle, LIVE/MANUAL/IDENTITY/
PARTNER sourcing, deviation guard, durable TTL, and operator override are implemented and tested.
Remaining ~10% is external-integration-gated (real Partner B HTTP client, real xe.com endpoint,
Redis/Postgres infra) and the docker-tagged Testcontainers ITs that only run on CI.

## INTEGRATION REQUESTS
1. **config-registry** — extend the partner pricing-rule API
   (`GET /v1/partners/{code}/rules`) to emit `rateCollSource` and `ratePaySource`
   (one of IDENTITY/LIVE/MANUAL/PARTNER) per rule. rate-fx already reads them (null ⇒ LIVE); until
   config-registry sends them, every leg defaults to LIVE and the PARTNER path is reachable only via
   manually-seeded PARTNER snapshots.
2. **payment/orchestration service** — at `POST /payments` commit time for a PARTNER-sourced quote,
   call rate-fx's commit-time deviation guard (currently `QuoteIssueService.resolvePartnerCommitRate`;
   a thin `POST /v1/quotes/{id}/commit-rate` endpoint can be exposed on request) BEFORE the
   irreversible scheme submit, and treat `PARTNER_B_QUOTE_DEVIATION` (422) /
   `PARTNER_B_QUOTE_UNAVAILABLE` (503) as hard declines.
3. **infra / ops** — provision the real Partner B quote feed. Today `SnapshotPartnerBQuotePort` reads
   PARTNER rows from `rate_snapshots` (seeded via `POST /v1/rates/snapshots`); a real HTTP client
   should be added as a `@Primary PartnerBQuotePort` (or behind a profile) so it supersedes the
   snapshot fallback without touching callers.

## Remaining (top items)
- Real Partner B HTTP client behind `PartnerBQuotePort` (currently in-process snapshot fallback).
- Expose the commit-time deviation guard as a REST endpoint once the payment service confirms the
  call shape (method is implemented and unit-tested; not yet HTTP-surfaced).
- Postgres Testcontainers ITs for the new admin/PARTNER paths (docker-tagged; CI-only).

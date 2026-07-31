> 작업: T1-3 portal real data / 출처: agent

# T1-3 — Partner Portal pages read real data

**Gap:** every read a partner saw in the portal was a fixture. No `RestApiKeyClient` and no
`RestStatementClient` existed at all; `/webhooks` and `/profile` were hardcoded inside
`PartnerPortalController`; `GMEPAY_PREFUNDING_CLIENT` was never set to `rest`, so Overview/Balance
404'd for real partners and only served `partner_test_001..003`.

**Status:** `[~]` — all five pages now read the owning service. The residual is that the portal is
read-**only** (writes are T1-5, an open product decision) plus operational/live-fleet items listed
at the end.

---

## The root cause nobody had named: code vs. numeric id

Four of the five pages were unreachable for the same underlying reason, not five separate ones.

The portal identifies a partner by **business CODE**: `/v1/portal/{partnerId}/**` is authorized
against the token's `partner_id` claim, and Keycloak seeds that claim with a seeded partner code
(`GMEREMIT`, `SENDMN` — `docker/keycloak/README.md`). But three upstreams key the partner by
config-registry's **numeric surrogate**:

| upstream | read | key |
|---|---|---|
| auth-identity | `GET /internal/auth/keys?partnerId=&environment=` | `Long` |
| notification-webhook | `GET /v1/webhook-configs?partnerId=` | `Long` |
| transaction-mgmt | `GET /v1/transactions?partnerId=` | `Long` |

Nothing bridged the two. The consequence was worse than "no client exists": `RestTransactionMgmtClient`
was **correctly** failing closed on a non-numeric partner id (empty page rather than an unscoped
query), which meant a real partner's **Transactions page and CSV statement silently returned
nothing**, while `partner_test_001..003` appeared to work because they were served by the stub. That
is the kind of gap that reads as "the platform has no traffic" rather than "the platform cannot
answer".

**Fix — `services/ops-partner-bff/.../client/PartnerDirectory.java`.** One `GET /v1/partners/{code}`
read, exposing `PartnerView.id`. Successful resolutions are cached for the JVM's life (the mapping is
immutable — `partner_code` freezes once `go_live_at` is stamped, per ADR-011 /
`PartnerImmutabilityGuard`); **failures** are cached only 30s so a partner activated moments ago, or a
config-registry restart, is picked up without bouncing the BFF, while a hot loop of bad codes still
cannot hammer the registry. An already-numeric id passes through untouched (the Admin surface).

The security property is preserved exactly: an unresolvable code returns `Optional.empty()` and every
caller fails closed. `partnerId` is always on the wire, so no upstream can ever be asked for "all
partners".

---

## Page by page

### 1. API keys — `RestApiKeyClient` (new)

No list-by-partner endpoint had to be added: auth-identity's `ApiKeyAdminController` already has
`GET /internal/auth/keys?partnerId=&environment=`. It is scoped to **one** environment per call, so
the client queries the `PRODUCTION` and `SANDBOX` rosters and merges them newest-first — a partner's
key list is their whole credential set, and hiding the sandbox half would misrepresent it.

Presents `X-Gme-Internal` from `gmepay.auth-identity.internal-secret` exactly as its sibling
`RestSandboxKeyClient` does (auth-identity's whole `/internal/**` surface is gated — T0-2); a blank
secret sends no header and logs a WARN rather than fabricating a credential.

**Minimal auth-identity change:** `KeyListItem` gained the real `status` (`api_keys.status`:
`ACTIVE | PENDING_EXPIRY | REVOKED`) and `expiresAt` columns. Without `status` the portal could not
distinguish a usable key from a revoked one — and the fabricated `PRIMARY`/`ROTATING` labels it used
to show were not a status at all. No new endpoint, no schema change.

Secret hygiene: the wire record has **no** secret/hash field, so even a future upstream that emitted
one could not surface it. Pinned by a test.

### 2. Statement — `RestStatementClient` (new)

Built from persisted transaction-mgmt rows, read through the **same** `TransactionMgmtClient` the
Transactions page uses — so the statement and the page cannot disagree, and the partner-scoping /
fail-closed rules apply to the CSV for free. Pages upstream to a 50 000-row cap (a truncated file logs
a WARN — it is never silently partial), forwards the date window to the service that owns the rows,
and sorts oldest-first.

The **CSV contract is unchanged**: `UC10_HEADER` is byte-identical to what the SPA already downloads,
money still rides as decimal strings per MONEY_CONVENTION.md, and revenue columns remain absent
(Admin-only). Added RFC-4180 quoting so a merchant- or scheme-supplied string cannot inject a column
break into a partner's statement.

Absent money fields stay **empty**, never zero-filled — writing `0` for an FX rate that was never
applied, or a prefunding deduction that has not happened, would misstate a finance document.

### 3. Webhooks — `PortalWebhookClient` / `RestPortalWebhookClient` (new)

Reads notification-webhook's real `GET /v1/webhook-configs?partnerId=` (JPA-backed
`webhook_endpoint`; the in-memory store is test-only). Deliberately a separate seam from the existing
`WebhookOpsClient`, which is the **operator** view of the same service (delivery backlog + replay) —
different question, different audience.

This replaces two rows built inline in the controller at `partner.example.com/{code}/webhook/payments`
and `.../settlements`, both `ACTIVE`, with a literal `Instant.parse("2026-06-09T11:00:00Z")` — identical
for every partner, under a domain nobody owns, on a page that had never consulted the service that
delivers webhooks.

### 4. Profile — real `onboardedAt`

`onboardedAt` is now V025 `partners.go_live_at`, the instant of the partner's **first** activation
(a later `SUSPENDED → LIVE` reactivation does not move it). It replaces a constant
`2026-01-01T00:00:00Z` returned for every partner.

This needed a new `PartnerView.goLiveAt` field (`libs/lib-api-contracts`) mapped in config-registry's
`PartnerDraftService.toView`. **Explicitly rejected alternative:** back-filling from `validFrom` /
`recordedAt`. Those are SCD-6 bitemporal stamps that move on **every** registry edit, so they would
have rendered a plausible but wrong onboarding date — strictly worse than an honest blank, because
nobody would question it.

### 5. Prefunding — the selector that never existed

`gmepay.prefunding.client` was **not declared** in the BFF's `application.properties` and was set on
no deploy target, so `RestPrefundingClient` never activated and `StubPrefundingClient` — whose only
rows are `partner_test_001..003` — answered in production.

Both it and the new `gmepay.notification-webhook.client` are now declared and set to `rest` on **all
three** surfaces, mirroring the convergence commit's convention:

| surface | change |
|---|---|
| `docker-compose.yml` | `GMEPAY_PREFUNDING_CLIENT`, `GMEPAY_NOTIFICATION_WEBHOOK_{CLIENT,BASE_URL}` |
| `deploy/helm/gmepay/values.yaml` | same keys (internal-auth secret already in `envSecretKeys`) |
| `run-fleet.ps1` | all five selectors + all five base-urls pinned to the 18xxx host band |

`RestPrefundingClient` already presented the internal-auth token (prefunding's balance API is gated
since T0-5), so no new secret plumbing was required — verified by the wiring guard.

---

## The stubs stopped lying

The three fixture sources were the *actual* partner-facing surface, because no rest client existed to
displace them. They now report honestly empty, so a mis-wired deployment shows an empty state instead
of a plausible falsehood:

- `StubApiKeyClient` — was two `gpk_live_<hash>` credentials per partner (from `partnerId.hashCode()`),
  indistinguishable from production keys; a partner could have tried to integrate against a prefix
  that authenticates nothing. Now an empty list.
- `StubStatementClient` — was five hardcoded `TXN-1001..1005` rows, all `zeropay_kr`, all at rate
  `1325.00000000`, even filtered by the date picker so the file looked responsive. A statement is a
  finance document. Now the header row alone (byte-identical header, so the download contract holds).
- webhooks — was inline in the controller. Now `StubPortalWebhookClient`, empty.

---

## Fields with NO real source (absent, not faked)

Per the scope rule, these are `null`/empty and render as an em dash. **None** is back-filled:

| field | why there is no source |
|---|---|
| API key `name` | no name column on `api_keys` (V002); no other service owns one |
| API key `scopes` | per-key scopes are not modelled — authorization comes from the principal's RBAC grants, not the key |
| API key `lastUsedAt` | no `last_used_at` column, so key usage is recorded nowhere |
| webhook `lastDeliveredAt` | notification-webhook exposes an aggregate delivery backlog + per-delivery replay only; no per-endpoint last-delivery read |
| `onboardedAt` (pre-activation) | `go_live_at` is NULL until the first `UAT → LIVE` transition |

The API Keys page carries a visible caption explaining why those three columns are blank, so an em
dash does not read as lost data. The Webhooks page tooltips the same for `lastDeliveredAt`. The real
`environment` column (`SANDBOX`/`PRODUCTION`) was added to the page — it is the field that actually
distinguishes a test key from a live one, which the fabricated `PRIMARY`/`ROTATING` labels never did.

---

## Scope line held

No partner self-serve **writes** were built — no sign-up, no key rotation UI, no webhook editing.
That is T1-5, an open product decision. The existing Phase-2 "coming soon" banners on the API Keys
and Webhooks pages are untouched.

---

## Verification

| check | result |
|---|---|
| `:services:ops-partner-bff:test` | **417 tests, 0 failures** (was 371 → 46 net new) |
| `:services:auth-identity:test` | green |
| `:services:config-registry:test` / `notification-webhook` / `transaction-mgmt` | green |
| `gradlew testClasses` (repo-wide) | green |
| partner-portal-ui `npx next build` | clean, 13 routes |
| portal vitest | **186 tests, 0 failures** — run from a real COPY under the scratchpad (the `'+'` path bug: vite URL-decodes the `+`, and a junction is not enough since vite resolves the real path; `node_modules` junctioned in) |
| `scripts/check_internal_auth_wiring.py` | 67/67 |
| `docker/keycloak/check-topology.mjs` | 101/101 |
| PyYAML parse — compose + all 4 Helm values | all parse; selectors asserted present and identical on both surfaces |

New test coverage (all authenticating properly through `OpsRbacGuard` — no security bypass; the
portal tests use a **partner-scoped** token, not an operator cross-read shortcut):

- `RestApiKeyClientTest` (9) — real rows from both rosters newest-first, scoping by resolved
  surrogate, absent fields not fabricated, unresolvable partner issues **no HTTP call at all**,
  outage → empty, internal-auth header present / absent-when-blank, secret material cannot ride.
- `RestStatementClientTest` (8) — cell-exact CSV from real rows, window forwarded upstream, empty
  range → header only, absent money stays empty, no revenue columns, comma/quote injection escaped,
  pagination.
- `RestPortalWebhookClientTest` (7) — real endpoints, `lastDeliveredAt` absent, null `eventTypes` →
  empty (not an invented roster), INACTIVE labelled, fail-closed, outage → empty, no secret.
- `PartnerDirectoryTest` (9) — resolution, numeric pass-through without a lookup, unknown/blank/null
  surrogate → empty, positive caching, negative throttling + invalidation, trimming.
- `PortalRealDataControllerTest` (10) — webhooks/profile end-to-end incl. **A-cannot-read-B returns
  403** for both, real `goLiveAt`, null for a not-yet-live partner, legacy-summary fallback omits
  `onboardedAt`, and regression asserts that `partner.example.com` and `2026-01-01T00:00:00Z` cannot
  come back.

**Not verified:** none of this has been exercised against a live fleet. Verification is
unit/contract-level plus static manifest guards.

---

## Still open / follow-ups

1. **Nothing writes.** Read-only by design — T1-5.
2. **Balances need provisioning.** A real partner needs a `partner_balance` row in prefunding; absent
   it the Balance page 404s. Honest, but not "working balances" — an operational step, not a code gap.
3. **`notification-webhook` is not in `run-fleet.ps1`'s `money` subset**, so the Webhooks page
   degrades to empty there. Left alone deliberately: it is not a money service.
4. **`FlywheelController` / `DeliveryOverviewController`** still approximate partner onboarding with
   `validFrom` even though `goLiveAt` is now available on `PartnerView`. Admin dashboards, out of this
   gap's scope — worth a one-line follow-up.
5. **`PartnerProfile` / `PartnerSummary` remain `@Deprecated(forRemoval)`** Expand-phase aliases; the
   Contract migration to bind the SPA directly to `PartnerView` is still outstanding.

## Note on the shared working tree

A second agent was concurrently editing `services/payment-executor`, `libs/lib-errors`, root
`build.gradle`, the Helm overlays and `Documentation/RUNBOOK_MONITORING.md` (gap T3-2/T3-3). Their
in-flight changes were **left uncommitted and untouched** — this commit stages only the portal files,
rather than `git add -A`, so their partial work is not swept in under this message. My manifest
wiring had already been secured in the interrupted-WIP commit `dd2445e`.

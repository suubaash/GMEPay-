> 작업: Business Development round-2 audit / 출처: agent

# GMEPay+ — Commercial 2nd-Pass Audit (post Ops-backend build)
**Lens:** Head of Business Development / Commercial. Date: 2026-07-01.
**Trigger:** Since round-1, an internal Operations backend shipped — kill-switch (pause/maintenance), per-entity suspend of partner·scheme·route, an operational gate in payment-executor that now REJECTS new authorizations with `SYSTEM_PAUSED`/`PARTNER_SUSPENDED`/`SCHEME_SUSPENDED`/`ROUTE_SUSPENDED`, ops decline/stuck/backlog alerts, and a control-tower + operator actions in the ops BFF. All of it is internal/ops-facing. That build created a new class of **partner-facing** gap: GME can now unilaterally cut a partner off, but has built nothing to *tell the partner*. This pass finds those newly-exposed gaps; it does not repeat round-1's 22-item commercial list.

**What I verified in code:**
- `OpsControlService.suspend/pause/maintenance` write ONLY an internal hash-chain audit row (operator + reason). No event emitted, no partner notification, no webhook. `reason` is captured but stays inside Ops.
- `OperationalGate.checkNewAuthorization` throws `OperationalGateException` → `PaymentExceptionHandler` returns HTTP 503 with the raw string code via the `ApiError` string ctor. These codes are NOT `ErrorCode` enum members (lib-errors frozen) — so they are undocumented, not in the partner error catalog, and only differ from a genuine outage by an unpublished string.
- `notification-webhook` knows exactly one event type: `payment.approved`. No `partner.suspended` / `system.paused` / `service.status` event exists.
- `partner-portal-ui` pages: balance, transactions, statement, webhooks, api-keys, profile, overview. There is NO status / incident / service-health / decline-insight page.

---

## (a) NEWLY-EXPOSED PARTNER-FACING GAPS (created by the Ops build)

1. **Suspension/pause comms channel — the trust-destroying surprise.** When GME suspends a partner or pauses the platform, the partner is told NOTHING. They simply start getting 503s. The operator even TYPES a `reason` — it is written to the internal audit chain and thrown away from the partner's perspective. A partner discovering they've been cut off from their live payment rail via a spike of rejects, with no notice, no reason, no ETA, and no one to call, is the single fastest way to lose a partner and poison every reference sale. **Need:** on suspend/pause, an automatic partner notification (email + the `partner.suspended`/`service.degraded` webhook event that the pipeline is one event-type away from supporting) carrying scope, reason, and expected-resolution/ETA.

2. **Partner-facing status page / incident comms.** There is no partner-visible service-status surface at all — not in the portal, not standalone. `operationalStatus` (paused/maintenance/suspended lists + reason + since) already exists as a computed read model inside the ops BFF control-tower; today only GME operators can see it. A partner integrating against us cannot answer "is it them or is it me?" during an incident. **Need:** a partner-scoped status/incident page (or portal widget) projecting the *partner's own* operational status (am I suspended? is the platform in maintenance? since when? why? ETA?) plus a maintenance calendar. This is table-stakes for any partner tech team and directly de-risks gap #1.

3. **`PARTNER_SUSPENDED`/`SYSTEM_PAUSED` as documented, contract-grade API errors (developer-experience gap).** The gate now emits four new wire codes as bare strings that are not enum members, not in any published error catalog, and carry no `Retry-After` or resume signal. To a partner's integration they are indistinguishable from a random 503 — so partners will either hammer retries (amplifying the incident) or hard-fail. **Need:** promote these to canonical documented errors with stable codes, clear retry semantics (`retryable=true` is set but there's no `Retry-After`/backoff guidance), and a doc explaining each — a prerequisite the moment the round-1 developer portal/error catalog is built.

4. **Partner-facing decline / health transparency (round-1 retention feature, now with new data available).** The Ops build produced exactly the signals partners have been asking for — decline-spike alerts, stuck/backlog detection, per-partner health — but pointed them ALL inward to operators. This is new data that makes round-1's #13/#14 (partner growth analytics + decline transparency) suddenly cheap to expose: give the partner their own decline-reason breakdown and health/uptime view derived from the same signals. Highest-leverage retention feature we now have the data for and still don't surface.

5. **Commercial-SLA / contractual implication of a unilateral suspend.** GME can now instantly and unilaterally suspend a partner or pause the whole platform — a powerful operational tool with an unbuilt commercial contract behind it. There is no notion of contractual notice period, no distinction between emergency suspension (fraud/risk — no notice) vs commercial suspension (billing dispute — notice required), no service-credit/SLA-breach accounting when GME's own pause causes partner downtime, and no partner-visible record of the event for dispute purposes. **Need:** tie the suspend/pause action to the (unbuilt, round-1 #16/#18) committed-SLA + contract layer so a unilateral cut-off is governed by agreed terms and auto-computes any service credits.

---

## (b) TOP UNBUILT GROWTH LEVERS (reaffirmed from round-1 4.19 — the 2-3 that most gate growth)

1. **Developer portal + public API docs + self-serve sandbox keys + SDKs + API versioning (round-1 A: #1-#4).** Still the #1 throttle. Every partner integration needs a GME engineer on a call; we cannot onboard partners in parallel. Nothing here shipped, and the Ops build just added four new undocumented error codes that make the missing error catalog more acute. This one cluster is the difference between onboarding partners serially and onboarding them at scale — it gates every other growth motion.

2. **Self-service trial front door + go-live certification (round-1 B: #5-#6).** We remain invisible until a salesperson manually opens an ops case; onboarding assumes an already-contracted partner. A prospect cannot request sandbox access, run a green test, and self-certify to production. This is the top-of-funnel that turns marketing interest into pipeline — without it BD has no scalable lead→live path.

3. **Flexible/tiered + promotional pricing (round-1 C: #8-#9).** Pricing is still one flat margin per partner×scheme×direction. No volume tiers, minimum commitments, rebates, or effective-dated promo rates. These are the standard levers to *close* large partners and to *win a corridor launch* against an incumbent — the absence caps deal size and makes us uncompetitive on new-corridor land-grabs.

(Corridor launch tracker, partner tiering/SLAs, CRM/health-scoring, coverage map, referral, white-label/multi-language all remain unbuilt too — but the three above are the binding constraints on top-of-funnel and deal velocity right now.)

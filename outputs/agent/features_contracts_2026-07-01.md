# GMEPay+ — Inter-Service Contracts, Settlement Flow, Events & ADRs

_Survey date: 2026-07-01. Read-only digest. Sources: `docs/INTER_SERVICE_CONTRACTS.md`,
`docs/SERVICE_MAP.md`, `docs/MONEY_CONVENTION.md`, `Documentation/SETTLEMENT_FLOW_SPEC.md`
(2026-06-24 draft), `docs/adr/ADR-001..016`._

---

## 0. MSA ground rules (non-negotiable)

- **1 service = 1 repo/module + its own datastore.** No service reads/writes another's DB — it calls the owner's API or consumes its event.
- **Sync REST/JSON** on the live payment path (p95 targets); **async Kafka via transactional Outbox** for notifications/settlement/reporting (retryable, DLQ).
- **`shared-libs` = build-time contracts only** (`lib-money`, `lib-errors`, `lib-events` schemas, `lib-api-contracts` OpenAPI DTOs/clients). No DB-bound entities, no cross-service data access.
- Service-owned JPA entities stay private; consumers use generated DTOs.
- Auth: partner HMAC at `api-gateway`, operator OIDC at `auth-identity`; internal calls use service identity (mTLS / signed JWT).

**DB ownership (one DB per owner):** `config`→config-registry · `txn`(+outbox)→transaction-mgmt · `prefunding`→prefunding · `ledger`→revenue-ledger · `settlement`→settlement-reconciliation · Mongo `merchant`→merchant-qr-data · Redis per-service (namespaced) · object store→scheme-adapter / reporting.

---

## 1. Inter-service call graph (who calls whom)

- **api-gateway** — public `/v1/*` partner surface; routes to public services; calls **auth-identity** (sync).
- **auth-identity** — exposes `/internal/auth/verify`, JWT issue/verify; calls **config-registry** for partner creds. (Machine auth only; humans → Keycloak per ADR-011.)
- **config-registry** — source of truth (`/v1/schemes|partners|rules|treasury-rates`); calls nothing.
- **rate-fx** — `POST /v1/rates` (quote) + event `rate.quoted`; calls **config-registry** (margins, treasury rates) sync.
- **smart-router** — `GET /v1/route`; calls **config-registry** (schemes) sync. (ADR-016: returns an *ordered candidate partner list* for `(network,country,mode,direction)`.)
- **qr-service** — `/v1/qr/parse`, `/v1/qr/cpm/generate`; calls **merchant-qr-data** to resolve merchant.
- **prefunding** — `/v1/prefunding/{partner}/balance|deduct|credit` (+ `reserve/hold` per spec) + event `prefunding.low`; calls nothing.
- **payment-executor** (orchestrator) — `POST /v1/payments`, `/cpm/generate`, `/cancel`; events `payment.approved|failed`. Calls **rate-fx, config-registry, smart-router, qr-service, prefunding (deduct/reserve), scheme-adapter, transaction-mgmt** — all sync.
- **transaction-mgmt** — `/v1/transactions/{id}` + state ops; emits `transaction.*`. Owns txn store + outbox.
- **scheme-adapter-zeropay** — `/internal/scheme/zeropay/submit`, SFTP batch, event `scheme.result`; calls **merchant-qr-data**; writes sync results back to payment-executor via event. (Future: SFTP brokered by planned **sftp-gateway**.)
- **merchant-qr-data** — `GET /v1/merchants/{qr}` (Mongo mirror).
- **notification-webhook** — **consumes** `payment.*`, `settlement.completed`, `prefunding.low` (async only).
- **settlement-reconciliation** — `/v1/settlements` + recon status; calls transaction-mgmt (sync/event) + scheme-adapter files; **emits `settlement.completed`**.
- **revenue-ledger** — `/v1/revenue`; **consumes** `payment.approved`, `settlement.completed` (async).
- **reporting-compliance** — `/v1/reports`, BOK FX1014/1015 export; reads revenue-ledger + transaction-mgmt.
- **ops-partner-bff** — 19-endpoint read aggregation over ~10 services for admin-ui / partner-portal-ui.

**Live sync path:** partner → api-gateway → payment-executor → { smart-router (scheme) → config-registry (rule/margins) → rate-fx (quote+lock) → prefunding (deduct) → scheme-adapter (submit ZeroPay) → transaction-mgmt (commit) } ⇒ Kafka `payment.approved` → { notification-webhook, revenue-ledger }.

---

## 2. Settlement / money flow spec (2026-06-24, authoritative over code)

**Parties (4):** Customer (belongs to the **partner**), Wallet Partner (SendMN/GME Remit), GME, QR Scheme (ZeroPay). Three settlement relationships + one billing:
- Customer → pays partner · Partner → funds GME (prepaid float) · GME → funds scheme (prepaid float) · Merchant → pays GME (out-of-band MDR).

**Two foundational rules:**
1. **Only the partner can charge the customer.** Scheme/GME can only *request* it. Collapses MPM and CPM into one model.
2. **Non-negotiable ordering:** GME never submits to the scheme (irreversible "pay merchant") until the partner confirms the wallet charge. Enforced by GME's ordering, not scheme atomicity.

**3-tier rate cascade (D2):** Scheme prices in KRW (merchant payout) → **+ GME margin** → GME quotes partner in `settle_a_ccy` (KRW **or** USD per contract, D3) → **+ partner margin** (added in partner app, optional/undisclosed, D13) → partner quotes customer in wallet ccy. **FX location follows D3:** settle=KRW → partner does KRW→wallet FX (GME no FX on partner leg); settle=USD → GME does KRW→USD. **GME bears FX risk (D4)** bounded by quote TTL **900s** (`RATE_QUOTE_EXPIRED`).

**Double prefund (D1):** partner holds float with GME; GME holds float with scheme; both debited per txn. Per-partner credit limit (D7): `available = balance + (allow_credit ? credit_limit : 0)`; `0` ⇒ hard-decline at zero. Per-scheme credit limit (D8): scheme's balance-check API is runtime authority.

**Two-phase flow (D6) — settlement waterfall:**
- **Phase 1 AUTHORIZE (nothing irreversible):** partner→GME `/authorize`; GME checks partner float (balance+credit) & **RESERVES**; GME does scheme balance-check; returns AUTHORIZED (auth id) or clean DECLINE (nothing owed).
- **Phase 2 CONFIRM (money now real):** partner debits customer wallet → partner→GME `/{authId}/confirm` (wallet-charge ref); GME **submits 0200 to scheme** (pay merchant vs GME scheme float) → 0210 approved; GME **CAPTURES** the reserved float (debit incl. service fee); SUCCESS + signed webhook.
- **Waterfall:** customer → partner float (GME) → GME scheme float → merchant payout. Merchant receives full amount (no deduction).

**Rollback matrix:** float/scheme short in P1 → clean decline; customer no money → partner never confirms, GME releases reservation; partner never confirms → reservation timeout-releases; **scheme declines AFTER customer charged (the only ugly case) → auto-refund customer + release reservation** (minimised by P1 balance-check).

**3 revenue streams (D9):** FX margin (always) + service fee (always, GME→partner `partner_fee_schedule` fixed+bps, D10, passed to customer) + merchant MDR (conditional; ZeroPay returns fee in 전문 field 41, GME bills merchant out-of-band + shares configurable cut with ZeroPay, D11). **Refund = full mirror reversal at locked original rate, no extra FX gain/loss (D12).** **CPM = same money model as MPM (D14)** — differs only at the front door (trigger + partner-scoped token). Rounding: precise pool math `MathContext(20)` HALF_UP; partner liability booked under `Partner.settlementRoundingMode`; residual → balanced `REVENUE_ROUNDING` gain/loss (MONEY_CONVENTION).

**Known gap:** partner-float debit currently excludes the service fee (must be payout-cost + FX-margin + service-fee) — flagged reconciliation bug.

---

## 3. Event choreography

- **Outbox → Kafka:** every service writes events to a transactional outbox (in the same tx as the business write) behind broker-agnostic `lib-events EventPublisher`; `KafkaEventPublisher` (lib-events-kafka) drains outbox → Kafka. Confluent Schema Registry, BACKWARD compat, acks=all idempotent producer, per-aggregate key for ordering.
- **Topic naming:** `gmepay.<aggregate>.<event>` (e.g. `gmepay.payment.approved`, `gmepay.settlement.completed`, `gmepay.prefunding.low`, `gmepay.kyb.screening`, `gmepay.audit.<aggregate>`).
- **Producers→consumers:**
  - payment-executor emits **`payment.approved` / `payment.failed`** → consumed by **notification-webhook** (webhook/email) + **revenue-ledger** (FX-margin + fee capture).
  - transaction-mgmt emits **`transaction.*`** (owns outbox).
  - settlement-reconciliation emits **`settlement.completed`** → **revenue-ledger** + **notification-webhook**.
  - prefunding emits **`prefunding.low`** → notification-webhook.
  - rate-fx emits `rate.quoted`; scheme-adapter emits `scheme.result` (sync result back to payment-executor).
- Replay + consumer groups enable reconciliation and audit rebuilds; audit stream `gmepay.audit.<aggregate>` → Kafka Connect S3 sink → MinIO cold archive.

---

## 4. ADRs (one line each — coordination/architecture rules)

- **ADR-001 Kafka** — Apache Kafka + Confluent Schema Registry (BACKWARD); topics `gmepay.<aggregate>.<event>`, acks=all idempotent; RabbitMQ dropped.
- **ADR-002 Edge** — Nginx (TLS/WAF/rate-limit, only public entrypoint) layered in front of Spring Cloud Gateway (HMAC/idempotency/per-key limits); SCG never internet-facing.
- **ADR-003 Mongo mirror** — keep MongoDB scoped strictly to merchant-qr-data (read-mostly, rebuildable from KFTC); all financial data stays in per-service Postgres.
- **ADR-004 Base images** — prod/staging app images on Rocky Linux 9 minimal + Temurin 21 / Node 20; off-the-shelf infra stays upstream.
- **ADR-005 Elasticsearch** — ELK log store now (observability only, never a system of record); CQRS txn-search index deferred until Postgres proves insufficient.
- **ADR-006 Vault** — partner docs in MinIO `gmepay-partner-vault`, object-lock compliance mode 10yr + versioning, Vault-held per-partner keys (crypto-shred); S3-API-only seam (cloud-agnostic, portability guard).
- **ADR-007 Audit 3-tier** — hot INSERT-only Postgres `audit` DB with `prev_hash` chain + Kafka `gmepay.audit.*` stream + MinIO cold object-lock archive; uniform `lib-audit`.
- **ADR-008 4-eyes** — canonical `change_request` table per regulated aggregate + Spring State Machine (DRAFT→PROPOSED→APPROVED→APPLIED); `proposed_by ≠ approved_by`; only APPLIED mutates, in one tx with audit write.
- **ADR-009 KYB port** — vendor-agnostic `KybProvider` port in `lib-kyb` (screen/runFullKyb/subscribe); Rest/Mock/Stub/Composite adapters; transactability decided by operator status, never by screening alone.
- **ADR-010 Bitemporal** — every regulated partner-domain table gets valid_from/to (business time) + recorded_at/superseded_at (transaction time); SCD Type 6, rows never UPDATEd (supersede+insert); `findCurrent`/`findAsOf`.
- **ADR-011 Keycloak split** — Keycloak = human OIDC auth; auth-identity = machine auth (ApiKey/HMAC/mTLS/IP-allowlist) only; gateways become OAuth2 resource servers; retires `password=demo`.
- **ADR-012 Draft persistence** — 8-step Partner Setup wizard persists server-side per step (`.../draft/<id>/step-<n>`) as ONBOARDING + change_request DRAFT; resumable/transferable; 4-eyes at activation; pre-condition gate returns 422+unmet[].
- **ADR-013 Expand/contract** — Flyway 10, every breaking schema change ships as 3 releases (Expand→Backfill→Contract); `ddl-auto=none`; CI rejects in-place NOT NULL / unpaired DROP COLUMN.
- **ADR-014 KYB vendor** — Octa Solution (Korean KoFIU-integrated) as concrete `OctaKybAdapter` in kyb-adapter; StubKybAdapter until sandbox creds land; swap = config change.
- **ADR-015 Cloud-agnostic** — one build + one umbrella Helm chart (`deploy/helm/gmepay/`) + thin per-target value overlays (onprem/aws/azure); env-var ABI; **open-protocol-only rule** (S3/Kafka/OIDC/OTLP — no provider SDK); portability guard.
- **ADR-016 QR routing/failover** — route by QR's own EMVCo network GUID (not country); `smart-router` resolves data-driven `partner_scheme.network_identifier` → ordered candidate list; payment-executor `FailoverPaymentRouter` fails over on technical errors only, never on business declines, with mandatory anti-double-charge status-lookup guard + bounded MAX_HOPS; retires `NepalQrDetector`.

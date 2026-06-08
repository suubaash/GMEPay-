<!-- generated -->
# GMEPay+ — Microservice Rules & Inter-Service API Contracts

This governs how every service in `SERVICE_MAP.md` is built so the pieces integrate cleanly.

## MSA rules (non-negotiable)
1. **One service = one repo/module + its own datastore.** A service owns its tables/collections. No other service reads or writes them directly.
2. **No shared database.** If service A needs data owned by service B, it calls **B's API** (or consumes B's event) — never B's DB.
3. **No in-process coupling across services.** Cross-service interaction is over the network only:
   - **Sync REST/JSON** for request/response in the live payment path (must be fast, p95 targets in NFR-10).
   - **Async Kafka events** (via the transactional Outbox) for notifications, settlement, reporting — decoupled, retryable, DLQ.
4. **`shared-libs` is build-time contracts/utilities ONLY** — it must NOT contain business entities tied to a DB or any cross-service data access. Allowed: `lib-money` (BigDecimal/currency), `lib-errors` (error envelope), `lib-events` (event **schemas**), `lib-api-contracts` (OpenAPI-generated **DTOs/clients**). A consumer of another service uses that service's generated client/DTO from `lib-api-contracts`, not a shared entity.
5. **Service-owned domain models stay private.** e.g. the `Rule`/`Partner`/`Scheme` JPA entities live inside **config-registry**; other services receive them as DTOs from config-registry's API, not by importing its entities.
6. **Every service is independently deployable** (own Docker image, own Helm chart, own Flyway migrations against its own DB) and independently testable (Testcontainers spins its own DB; other services are stubbed with WireMock).
7. **Auth at the edge + service-to-service.** Public traffic authenticates at `api-gateway` (partner HMAC) / `auth-identity` (operator OAuth2). Internal calls use service identity (mTLS / signed JWT).

## Data ownership (one DB per owner)
| Datastore | Owner service | Holds |
|---|---|---|
| PostgreSQL `config` | config-registry | schemes, partners, rules, treasury rates |
| PostgreSQL `txn` | transaction-mgmt | transactions, event trail, outbox |
| PostgreSQL `prefunding` | prefunding | balances, ledger entries |
| PostgreSQL `ledger` | revenue-ledger | double-entry revenue ledger |
| PostgreSQL `settlement` | settlement-reconciliation | settlement batches, recon state |
| MongoDB `merchant` | merchant-qr-data | merchant + QR projections |
| Redis | (per service, namespaced) | quote TTL, idempotency keys, caches |
| Object storage | scheme-adapter-zeropay / reporting-compliance | SFTP files, reports, archives |

Other services NEVER connect to a DB they don't own — they call the owner's API.

## Inter-service contracts — who exposes what, who calls whom
Sync = REST call; Event = Kafka topic (async).

| Service | Exposes (API / events) | Consumes (calls) |
|---|---|---|
| **api-gateway** | public `/v1/*` (partner API surface) | routes to all public services; auth-identity (sync) |
| **auth-identity** | `/internal/auth/verify`, JWT issue/verify | config-registry (partner creds) |
| **config-registry** | `/v1/schemes`, `/v1/partners`, `/v1/rules`, `/v1/treasury-rates` | — (source of truth) |
| **rate-fx** | `POST /v1/rates` (quote); event `rate.quoted` | config-registry (rule margins, treasury rates) — sync |
| **smart-router** | `GET /v1/route` | config-registry (schemes) — sync |
| **qr-service** | `/v1/qr/parse`, `/v1/qr/cpm/generate` | merchant-qr-data (resolve merchant) — sync |
| **prefunding** | `/v1/prefunding/{partner}/balance`, `deduct`, `credit`; event `prefunding.low` | — |
| **payment-executor** | `POST /v1/payments`, `/v1/payments/cpm/generate`, `/cancel`; event `payment.approved`,`payment.failed` | rate-fx, prefunding (deduct), qr-service, config-registry, smart-router, scheme-adapter, transaction-mgmt — all sync |
| **transaction-mgmt** | `/v1/transactions/{id}`, state ops; events `transaction.*` | — (owns txn store + outbox) |
| **scheme-adapter-zeropay** | `/internal/scheme/zeropay/submit`; SFTP batch; events `scheme.result` | merchant-qr-data (sync); writes sync results back to payment-executor via event |
| **merchant-qr-data** | `GET /v1/merchants/{qr}` | scheme-adapter (receives sync files → updates store) |
| **notification-webhook** | webhook config API | **consumes events** `payment.*`, `settlement.completed`, `prefunding.low` (async) |
| **settlement-reconciliation** | `/v1/settlements`, recon status | transaction-mgmt (sync/event), scheme-adapter files; emits `settlement.completed` |
| **revenue-ledger** | `/v1/revenue` | **consumes events** `payment.approved`, `settlement.completed` (async) |
| **reporting-compliance** | `/v1/reports`, BOK FX1014/1015 export | revenue-ledger, transaction-mgmt (sync/event) |
| **bff (ops/partner)** | aggregation API for admin-ui / partner-portal-ui | calls many services' APIs (read) |

## Live payment flow (sync path, illustrative)
```
partner -> api-gateway -> payment-executor
   payment-executor -> smart-router        (resolve scheme)        [sync]
   payment-executor -> config-registry     (rule + margins)        [sync]
   payment-executor -> rate-fx             (quote + lock)          [sync]
   payment-executor -> prefunding          (atomic deduct, OVERSEAS)[sync]
   payment-executor -> scheme-adapter      (submit to ZeroPay)     [sync]
   payment-executor -> transaction-mgmt    (commit)                [sync]
   payment-executor ==> Kafka payment.approved
        notification-webhook (webhook/email)  [async]
        revenue-ledger (margin + fee capture)  [async]
```

## What this means for agents
- Build your service with **its own DB + migrations**, expose your API per the table above, and **stub the services you consume with WireMock** in tests.
- Define cross-service payloads in `lib-api-contracts` (OpenAPI) so the caller and owner agree on the contract.
- Never reach into another service's database or import its private entities — only its published API/DTO or its events.

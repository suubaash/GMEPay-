<!-- generated; manually amended 2026-06-10 (WBS v3: ops-partner-bff, sftp-gateway, lib-events-kafka — keep on regeneration) -->
# GMEPay+ — Microservice Partition & Build Order

Every WBS work-package is assigned to exactly one owning service so a Claude agent can build one repo/microservice at a time, then integrate. Shared contracts live in `shared-libs` and must be built first.

## Services & ownership

| # | Repo | Layer | WPs | Tickets | Hrs | WBS work-packages |
|---|---|---|---|---|---|---|
| 1 | `shared-libs` | platform | 7 | 187 | 111.9 | 2.1, 2.2, 2.4, 2.6, 3.1, 3.7, 8.7 |
| 2 | `platform-infra` | platform | 12 | 352 | 258.4 | 2.3, 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 16.1, 16.4, 16.6 |
| 3 | `config-registry` | backend | 3 | 96 | 52.4 | 2.5, 3.2, 3.4 |
| 4 | `rate-fx` | backend | 10 | 118 | 65.7 | 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 8.3 |
| 5 | `prefunding` | backend | 6 | 160 | 94.4 | 3.5, 6.1, 6.2, 6.3, 6.4, 6.5 |
| 6 | `qr-service` | backend | 2 | 47 | 29.8 | 5.3, 5.4 |
| 7 | `smart-router` | backend | 0 | 0 | 0.0 |  |
| 8 | `auth-identity` | backend | 3 | 80 | 51.8 | 8.2, 13.2, 13.9 |
| 9 | `payment-executor` | backend | 5 | 150 | 97.9 | 5.2, 5.5, 5.6, 5.8, 8.4 |
| 10 | `transaction-mgmt` | backend | 3 | 74 | 47.1 | 3.3, 5.1, 5.7 |
| 11 | `merchant-qr-data` | backend | 1 | 30 | 18.9 | 9.3 |
| 12 | `scheme-adapter-zeropay` | backend | 8 | 234 | 157.8 | 9.1, 9.2, 9.4, 9.5, 9.6, 9.7, 9.9, 9.10 |
| 13 | `notification-webhook` | backend | 1 | 26 | 17.2 | 8.6 |
| 14 | `settlement-reconciliation` | backend | 4 | 119 | 85.7 | 7.1, 7.4, 7.6, 9.8 |
| 15 | `revenue-ledger` | backend | 2 | 46 | 29.2 | 7.2, 7.3 |
| 16 | `reporting-compliance` | backend | 3 | 77 | 48.8 | 7.5, 13.7, 13.8 |
| 17 | `api-gateway` | backend | 5 | 135 | 83.6 | 8.1, 8.5, 8.8, 8.9, 8.10 |
| 18 | `ui-design-system` | frontend | 3 | 89 | 57.2 | 12.1, 12.4, 12.5 |
| 19 | `admin-ui` | frontend | 12 | 361 | 247.2 | 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 12.2 |
| 20 | `partner-portal-ui` | frontend | 8 | 240 | 154.8 | 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 12.3 |
| 21 | `security-platform` | platform | 7 | 199 | 146.2 | 3.6, 13.1, 13.3, 13.4, 13.5, 13.6, 13.10 |
| 22 | `qa-platform` | platform | 10 | 365 | 252.5 | 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10 |
| 23 | `program-mgmt` | program | 9 | 259 | 176.0 | 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 16.2, 16.3, 16.5 |
| 24 | `ops-partner-bff` | backend | — | — | — | 18.1 (legitimized by WBS v3; also 17.5, 18.4) |
| 25 | `sftp-gateway` | backend | — | — | — | 18.3 (planned — module not yet scaffolded) |

### WBS v3 additions (2026-06-10 re-baseline)

- **`ops-partner-bff`** (`services/ops-partner-bff`) — backend-for-frontend serving `admin-ui` and `partner-portal-ui`: a **19-endpoint** aggregation API speaking REST to 10 upstream services. Built ahead of WBS v2, legitimized by WBS v3 (WS 18.1); backlog bundle: `services_backlog/ops-partner-bff.md`.
- **`sftp-gateway`** (`services/sftp-gateway`, **planned — WS 18.3**) — new service from the architecture diagram: brokers all KFTC SFTP file exchange so scheme adapters never touch raw SFTP; bridges SFTP ↔ MinIO with PGP + checksum ledger. Backlog bundle: `services_backlog/sftp-gateway.md`.
- **`libs/lib-events-kafka`** (new shared lib, **ADR-001** — `docs/adr/ADR-001-message-broker-kafka.md`) — Kafka transport behind the broker-agnostic `lib-events` `EventPublisher` interface (`KafkaEventPublisher`, outbox → Kafka drain, consumers; tickets 17.4-G01..G05). Owned with `shared-libs`.

## Suggested build / integration order

Build top-to-bottom; within a tier, services are independent and can run in parallel agents.

- **Tier 0 — contracts & platform:** `shared-libs` (incl. `libs/lib-events-kafka`), `platform-infra`
- **Tier 1 — core config & money:** `config-registry`, `rate-fx`, `prefunding`, `qr-service`, `smart-router`, `auth-identity`
- **Tier 2 — orchestration:** `payment-executor`, `transaction-mgmt`, `merchant-qr-data`, `scheme-adapter-zeropay`, `notification-webhook`, `sftp-gateway` (planned, WS 18.3)
- **Tier 3 — finance:** `settlement-reconciliation`, `revenue-ledger`, `reporting-compliance`
- **Tier 4 — edge & UI:** `api-gateway`, `ops-partner-bff`, `ui-design-system`, `admin-ui`, `partner-portal-ui`
- **Cross-cutting (ongoing):** `security-platform`, `qa-platform`, `program-mgmt`

## How an agent uses this

1. Pick one service. Open `services_backlog/<service>.md` — it lists every ticket (id, deliverable, acceptance checks) for that service only.
2. Build it in its own module/repo against the `shared-libs` contracts.
3. Integrate per the tier order above; `api-gateway` and the UIs come last.

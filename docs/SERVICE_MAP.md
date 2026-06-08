<!-- generated -->
# GMEPay+ — Microservice Partition & Build Order

Every WBS work-package is assigned to exactly one owning service so a Claude agent can build one repo/microservice at a time, then integrate. Shared contracts live in `shared-libs` and must be built first.

## Services & ownership

| # | Repo | Layer | WPs | Tickets | Hrs | WBS work-packages |
|---|---|---|---|---|---|---|
| 1 | `shared-libs` | platform | 7 | 187 | 111.9 | 2.1, 2.2, 2.4, 2.6, 3.1, 3.7, 8.7 |
| 2 | `platform-infra` | platform | 12 | 352 | 258.4 | 2.3, 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 16.1, 16.4, 16.6 |
| 3 | `config-registry` | backend | 3 | 92 | 50.7 | 2.5, 3.2, 3.4 |
| 4 | `rate-fx` | backend | 10 | 118 | 65.7 | 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 8.3 |
| 5 | `prefunding` | backend | 6 | 160 | 94.4 | 3.5, 6.1, 6.2, 6.3, 6.4, 6.5 |
| 6 | `qr-service` | backend | 2 | 47 | 29.8 | 5.3, 5.4 |
| 7 | `smart-router` | backend | 0 | 0 | 0.0 |  |
| 8 | `auth-identity` | backend | 3 | 80 | 51.8 | 8.2, 13.2, 13.9 |
| 9 | `payment-executor` | backend | 5 | 147 | 96.2 | 5.2, 5.5, 5.6, 5.8, 8.4 |
| 10 | `transaction-mgmt` | backend | 3 | 73 | 46.6 | 3.3, 5.1, 5.7 |
| 11 | `merchant-qr-data` | backend | 1 | 30 | 18.9 | 9.3 |
| 12 | `scheme-adapter-zeropay` | backend | 8 | 234 | 157.8 | 9.1, 9.2, 9.4, 9.5, 9.6, 9.7, 9.9, 9.10 |
| 13 | `notification-webhook` | backend | 1 | 26 | 17.2 | 8.6 |
| 14 | `settlement-reconciliation` | backend | 4 | 119 | 85.7 | 7.1, 7.4, 7.6, 9.8 |
| 15 | `revenue-ledger` | backend | 2 | 45 | 28.8 | 7.2, 7.3 |
| 16 | `reporting-compliance` | backend | 3 | 77 | 48.8 | 7.5, 13.7, 13.8 |
| 17 | `api-gateway` | backend | 5 | 135 | 83.6 | 8.1, 8.5, 8.8, 8.9, 8.10 |
| 18 | `ui-design-system` | frontend | 3 | 89 | 57.2 | 12.1, 12.4, 12.5 |
| 19 | `admin-ui` | frontend | 12 | 360 | 246.6 | 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.11, 12.2 |
| 20 | `partner-portal-ui` | frontend | 8 | 240 | 154.8 | 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 12.3 |
| 21 | `security-platform` | platform | 7 | 199 | 146.2 | 3.6, 13.1, 13.3, 13.4, 13.5, 13.6, 13.10 |
| 22 | `qa-platform` | platform | 10 | 363 | 251.2 | 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 15.10 |
| 23 | `program-mgmt` | program | 9 | 259 | 176.0 | 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 16.2, 16.3, 16.5 |

## Suggested build / integration order

Build top-to-bottom; within a tier, services are independent and can run in parallel agents.

- **Tier 0 — contracts & platform:** `shared-libs`, `platform-infra`
- **Tier 1 — core config & money:** `config-registry`, `rate-fx`, `prefunding`, `qr-service`, `smart-router`, `auth-identity`
- **Tier 2 — orchestration:** `payment-executor`, `transaction-mgmt`, `merchant-qr-data`, `scheme-adapter-zeropay`, `notification-webhook`
- **Tier 3 — finance:** `settlement-reconciliation`, `revenue-ledger`, `reporting-compliance`
- **Tier 4 — edge & UI:** `api-gateway`, `ui-design-system`, `admin-ui`, `partner-portal-ui`
- **Cross-cutting (ongoing):** `security-platform`, `qa-platform`, `program-mgmt`

## How an agent uses this

1. Pick one service. Open `services_backlog/<service>.md` — it lists every ticket (id, deliverable, acceptance checks) for that service only.
2. Build it in its own module/repo against the `shared-libs` contracts.
3. Integrate per the tier order above; `api-gateway` and the UIs come last.

# GMEPay+ — Master Plan & Launch-Readiness Scorecard

**Target go-live:** 10 Oct 2026 (GME Remit domestic) · overseas partners Oct–Dec 2026
**Last updated:** 2026-06-08 · **Overall readiness (build-to-launch): ~4%**
**Status legend:** ✅ done/green · 🟡 in progress/partial · ⬜ not started · 🔒 blocked on external party

> Living document. Update the status columns and `PROGRESS.md` as each service goes green. Build = AI agents; calendar-bound certification/regulatory/UAT/infra = GME team + external parties.

---

## 1. Operating model
- One agent owns one microservice → its own repo/module + own DB, API-only communication (see `docs/INTER_SERVICE_CONTRACTS.md`).
- Green-gate: each service compiles + passes unit tests before commit.
- Every milestone = git commit + `PROGRESS.md` update → resumable across quota windows.
- Drivers: `docs/SERVICE_MAP.md` (what + build tiers), `Documentation/services_backlog/<service>.md` (per-service tickets), `docs/STACK.md` (stack).

---

## 2. Phase plan
| Phase | Objective | Primary owner | Exit gate | Status |
|---|---|---|---|---|
| **0. Foundation & contracts** | Monorepo, shared libs, frozen API/event contracts | AI agents | Contracts frozen, build green | 🟡 ~70% |
| **1. Per-service build** | All 22 services built, unit-tested, green | AI agents | Every service green | 🟡 ~10% |
| **2. Integration** | API/event wiring, contract tests, E2E money path on local stack | AI agents + GME CI | Money path E2E passes | ⬜ |
| **3. Infrastructure** | Postgres/Mongo/Redis/Kafka, K8s, CI/CD GitOps, observability | GME DevOps (agents author IaC) | Deploys to staging | ⬜ |
| **4. Hardening** | Auth/Vault/RBAC, performance/load, resilience, pen test | Agents author; GME validates | Perf + security pass | ⬜ |
| **5. Compliance & certification** | KFTC/ZeroPay cert, BOK FX1014/1015, tax-invoice, AML | 🔒 External + GME | Scheme-certified + regulatory sign-off | ⬜ 🔒 |
| **6. UAT & partner onboarding** | Business UAT, GME Remit ready, partner sandbox | GME business + partners | UAT signed | ⬜ |
| **7. Go-live & hypercare** | Production cutover, smoke, 14-day hypercare | GME + agents | Stable in production | ⬜ |

---

## 3. Per-service build scorecard (Phase 1)
Tickets from the service-partitioned backlog. % is rough build completion.

| Tier | Service | Tickets | Status | % | Notes |
|---|---|--:|---|--:|---|
| 0 | `shared-libs` | 187 | 🟡 | 30 | money/errors/events green; api-contracts, config & i18n frameworks pending |
| 0 | `platform-infra` | 352 | ⬜ | 0 | CI/CD, K8s, IaC, observability |
| 1 | `config-registry` | 92 | 🟡 | 10 | Rule + margin validation + endpoint; entities/treasury/persistence pending |
| 1 | `rate-fx` | 118 | 🟡 | 55 | 5-step engine + pool identity + `POST /v1/rates` green; sourcing/TTL/partner-B/persistence pending |
| 1 | `prefunding` | 160 | 🟡 | 30 | atomic deduction logic + service; DB-atomic, top-up flows, endpoints pending |
| 1 | `qr-service` | 47 | ⬜ | 0 | EMVCo parse, CPM generate |
| 1 | `smart-router` | 89* | 🟡 | 40 | routing + `GET /v1/route` green (*incl. UX design tickets) |
| 1 | `auth-identity` | 80 | ⬜ | 0 | HMAC, OAuth2/JWT, RBAC, key lifecycle |
| 2 | `payment-executor` | 147 | ⬜ | 0 | CPM/MPM orchestration, commit |
| 2 | `transaction-mgmt` | 73 | ⬜ | 0 | state machine, event trail, outbox |
| 2 | `merchant-qr-data` | 30 | ⬜ | 0 | merchant/QR store + sync |
| 2 | `scheme-adapter-zeropay` | 234 | ⬜ | 0 | ZeroPay REST+SFTP, ZP00xx |
| 2 | `notification-webhook` | 26 | ⬜ | 0 | signed webhooks, retry/DLQ |
| 3 | `settlement-reconciliation` | 119 | ⬜ | 0 | net/gross settlement, recon |
| 3 | `revenue-ledger` | 45 | ⬜ | 0 | double-entry, 70/30, FX margin |
| 3 | `reporting-compliance` | 77 | ⬜ | 0 | BOK FX1014/1015, reports |
| 4 | `api-gateway` | 135 | ⬜ | 0 | Spring Cloud Gateway, HMAC, /v1 |
| 4 | `ui-design-system` | 89 | ⬜ | 0 | React design system |
| 4 | `admin-ui` | 360 | ⬜ | 0 | Ops/Admin portal |
| 4 | `partner-portal-ui` | 240 | ⬜ | 0 | partner self-service |
| x | `security-platform` | 199 | ⬜ | 2 | error model only |
| x | `qa-platform` | 363 | 🟡 | 5 | unit tests for built pieces |
| x | `program-mgmt` | 259 | 🟡 | — | planning artifacts complete (non-code) |

---

## 4. Launch-readiness by dimension
| Dimension | Status | % | Blocker / dependency |
|---|---|--:|---|
| Specification & design | ✅ | 95 | — (spec, WBS, 3,432-ticket backlog, service map done) |
| Core money-path logic | 🟡 | 35 | rate engine green; executor/txn/settlement pending |
| Microservices built | 🟡 | 10 | Phase 1 in progress |
| Integration (API/events, E2E) | ⬜ | 0 | needs Docker/local stack (Phase 2) |
| Persistence & data | ⬜ | 8 | per-service DBs + migrations not yet built |
| Infrastructure & deploy | ⬜ | 3 | K8s/CI/CD/cloud (GME) |
| Security & auth | ⬜ | 2 | auth/Vault/RBAC + pen test |
| Front-end (Admin + Portal) | ⬜ | 0 | not started |
| Scheme connectivity (ZeroPay) | ⬜ 🔒 | 0 | KFTC test env (~mid-May), certification |
| Regulatory (BOK / tax-invoice) | ⬜ 🔒 | 0 | OI-03 format, OI-02 API |
| Testing (integration/perf/UAT) | 🟡 | 5 | unit only so far |
| **Overall build-to-launch** | 🟡 | **~4** | — |

---

## 5. Critical path & external blockers (🔒 not solvable by code)
- **KFTC / 한결원 ZeroPay test environment** available by ~mid-May 2026 → gates scheme integration + certification (Phase 5).
- **BOK FX reporting format** (OI-03) → gates `reporting-compliance` regulatory output.
- **Hometax tax-invoice API** (OI-02) → gates merchant monthly billing.
- **Customer-approval UX** (OI-01) → partner-app side; clarify flow.
- **Security/pen-test sign-off**, **UAT business sign-off**, **production cloud + deployment**, **partner onboarding & prefunding** — GME-owned, calendar-bound.

---

## 6. Open items register
| ID | Item | Owner | Needed by |
|---|---|---|---|
| OI-01 | Customer approval method (CPM/MPM) | GME Product + partners | Phase 6 |
| OI-02 | Tax-invoice (Hometax) API for Korean merchant billing | GME + KFTC/Hometax | Phase 5 |
| OI-03 | BOK reporting format/frequency/fields/channel (FX1014/1015) | GME Compliance + BOK | Phase 5 |

---

## 7. Definition of "go-live ready" (exit criteria)
- [ ] All Phase-1 services green; integration money-path E2E passes on a real stack.
- [ ] Deployed to staging on K8s with CI/CD, observability, backups/DR.
- [ ] Security hardened + pen-test passed; performance meets NFR-10 targets.
- [ ] ZeroPay **certified** with KFTC; BOK FX1014/1015 reporting verified; tax-invoice integrated.
- [ ] Admin System + Partner Portal usable; RBAC enforced.
- [ ] UAT signed off by GME Ops/Finance; GME Remit onboarded; rollback + runbook ready.
- [ ] Production cutover plan rehearsed; hypercare staffed.

---

## 8. Immediate next actions (AI agents)
1. Finish **Phase 0**: freeze `lib-api-contracts` (OpenAPI→DTOs) + `lib-events` schemas.
2. Build **Tier 0 → Tier 1** services from `services_backlog/*.md`, each green + committed + pushed.
3. Stand up **`docker-compose`** local stack and begin Phase-2 contract/E2E tests (in an environment with Docker).
4. Keep this scorecard + `PROGRESS.md` current at each checkpoint.

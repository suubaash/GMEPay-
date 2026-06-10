# GMEPay+ — Master Plan & Launch-Readiness Scorecard

**Target go-live:** 10 Oct 2026 (GME Remit domestic) · overseas partners Oct–Dec 2026
**Last updated:** 2026-06-10 (WBS v3 re-baseline) · **Overall readiness (sheet-level rollup of the ticket audit): 41 DONE (1.2%) · 412 PARTIAL (12.0%) · 2,979 NOT STARTED (86.8%) of the original 3,432 tickets** (+55 new v3 gap-closure tickets → backlog now 3,487). Combined Done+Partial = **~13%**, but most PARTIALs share one root cause: no Docker in the build env (H2 instead of PostgreSQL, stubs instead of live REST, log instead of Kafka). Earlier "% scaffolded" estimates of 30-50% reflected services *touched*, not acceptance checks met. See `docs/WBS_STATUS.md` (audit) and `docs/COMPLETION_PLAN_V3.md` (re-baselined execution plan).
**Status legend:** ✅ done/green · 🟡 in progress/partial · ⬜ not started · 🔒 blocked on external party

> Living document. Update the status columns and `PROGRESS.md` as each service goes green. Build = AI agents; calendar-bound certification/regulatory/UAT/infra = GME team + external parties.

---

## 1. Operating model
- One agent owns one microservice → its own repo/module + own DB, API-only communication (see `docs/INTER_SERVICE_CONTRACTS.md`).
- Green-gate: each service compiles + passes unit tests before commit.
- Every milestone = git commit + `PROGRESS.md` update → resumable across quota windows.
- Drivers: `docs/SERVICE_MAP.md` (what + build tiers), `Documentation/services_backlog/<service>.md` (per-service tickets), `docs/STACK.md` (stack).

---

## 2. Phase plan — **superseded by Completion Plan v3 (2026-06-10 re-baseline)**
> The audit showed the original phase plan tracked *workstreams*, not *unblocking order*. The WBS has been re-baselined: every work package now carries audit status + a completion phase **R0–R8**, and 3 new workstreams (17 Real-Stack Gap Closure · 18 Missing Components · 20 Phase-2+ Adapters, +55 tickets) were added. **See `docs/COMPLETION_PLAN_V3.md` and the “Completion Plan v3” sheet in `GMEPay+_WBS.xlsx`.**

| R-Phase | Objective | Tickets (D/P/N) | Exit gate | Status |
|---|---|---|---|---|
| **R0** Decisions & build env | Docker-capable CI; stack ADRs (Kafka/RabbitMQ, Gateway/Nginx, Mongo) | 190 (6/40/144) | compose + Testcontainers green in CI; ADRs signed | 🟡 |
| **R1** Persistence & real wiring | H2→PostgreSQL ×12, Redis, Kafka+Schema Registry, registries, Mongo, MinIO | 424 (3/81/340) | all stores real; restart-safe | 🟡 |
| **R2** Money-path E2E | Stub→REST flips; sftp-gateway; scripted E2E incl. rounding residual | 715 (20/131/564) | money-path E2E green in CI | 🟡 |
| **R3** Edge & security | Nginx WAF, real JWT/RBAC (kills `password=demo`), Vault | 272 (8/24/240) | authz matrix enforced | 🟡 |
| **R4** UI completion | All admin+portal screens vs live BFF; contract-drift CI gate | 717 (0/80/637) | every WBS screen functional | 🟡 |
| **R5** Infra & observability | K8s staging, OTel/Prometheus/Grafana/ELK/Jaeger, DR | 268 (0/0/268) | staging deploy + dashboards | ⬜ |
| **R6** Hardening & perf | Load vs NFR-10, resilience, pen test | 256 (1/14/241) | perf + security pass | ⬜ |
| **R7** Compliance & cert | KFTC/ZeroPay cert, BOK FX1014/1015, Hometax, AML | 392 (3/41/348) | certified + sign-offs | ⬜ 🔒 |
| **R8** UAT, go-live, hypercare | UAT, cutover, 14-day hypercare; Phase-2 adapters start | 253 (0/1/252) | stable in production | ⬜ |

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
| 1 | `qr-service` | 47 | 🟡 | 25 | module + API + EMVCo parse/CPM + 4 unit tests green; persistence pending |
| 1 | `smart-router` | 89* | 🟡 | 40 | routing + `GET /v1/route` green (*incl. UX design tickets) |
| 1 | `auth-identity` | 80 | 🟡 | 25 | module + /internal/auth/verify + HMAC/JWT + 3 tests green; RBAC/Vault pending |
| 2 | `payment-executor` | 147 | 🟡 | 25 | orchestrator + endpoints + prefund-before-scheme test green; real clients pending |
| 2 | `transaction-mgmt` | 73 | 🟡 | 25 | state machine + outbox record + transition tests green; persistence pending |
| 2 | `merchant-qr-data` | 30 | 🟡 | 25 | GET /v1/merchants/{qr} + lookup + tests green; Mongo + sync pending |
| 2 | `scheme-adapter-zeropay` | 234 | 🟡 | 20 | adapter iface + ZP0011 format/parse + code mapping + tests green; SFTP/full ZP00xx pending |
| 2 | `notification-webhook` | 26 | 🟡 | 25 | webhook signing + backoff/DLQ + tests green; Kafka consumers pending |
| 3 | `settlement-reconciliation` | 119 | 🟡 | 20 | net/gross calc + line matcher + 4 tests green; ZP file recon pending |
| 3 | `revenue-ledger` | 45 | 🟡 | 25 | double-entry posting + 70/30 split + tests green; persistence pending |
| 3 | `reporting-compliance` | 77 | 🟡 | 20 | FX1014/1015 field mapping + tests green; format pending OI-03 🔒 |
| 4 | `api-gateway` | 135 | 🟡 | 20 | Spring Cloud Gateway + HMAC/idempotency filters + tests green; routing/wiring pending |
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

## 8. Immediate next actions (AI agents) — v3 wave plan
1. **Wave 1 (R0):** 17.1-G01..03 — Docker-capable CI + make `docker-compose.yml` actually boot; 18.7-G01 — draft ADR-001..005 (**user decision needed**: Kafka vs RabbitMQ, Spring Cloud Gateway vs Nginx, MongoDB keep/drop, Rocky Linux, Elasticsearch).
2. **Wave 2 (R1):** 17.2-G01..12 H2→PostgreSQL (one agent per service, parallel) · 17.3 Redis ×3 · 17.4 Kafka + Schema Registry ×5.
3. **Wave 3 (R2/R3):** 17.5 Stub→REST flips E2E · 17.6 registry persistence · 18.6 money-path E2E harness (incl. ROUND_DOWN partner case) · 18.3 sftp-gateway · 18.4 real auth.
4. Pull forward any **R7 work not gated on the KFTC env** (file formats, mappings, BOK capture) whenever an agent is free.
5. Keep this scorecard + `PROGRESS.md` + the Backlog `Status` column current at each checkpoint.

## 9. Change log
- **2026-06-10 — WBS v3 re-baseline (Completion Plan).** Post-audit re-baseline: all 124 WPs got audit-status columns + an R0–R8 completion-phase assignment; added WS 17 (Real-Stack Gap Closure), WS 18 (Missing Components incl. `ops-partner-bff` legitimization, `sftp-gateway`, real auth, Nginx WAF, E2E harness, stack ADRs) and WS 20 (Phase-2+ adapters) — **+20 WPs, +55 tickets (3,432→3,487)**. New sheet “Completion Plan v3” in `GMEPay+_WBS.xlsx`; narrative in `docs/COMPLETION_PLAN_V3.md`; per-service `-Gxx` tickets appended to `Documentation/services_backlog/` (2 new bundles: `ops-partner-bff`, `sftp-gateway`). Sheet-level status rollup is now canonical: 41 D / 412 P / 2,979 N of the original 3,432.
- **2026-06-08 — Addendum 001: Per-partner settlement rounding.** Added `Partner.settlement_rounding_mode` (default HALF_UP); settlement liability booked under the partner's rule with the residual posted to `REVENUE_ROUNDING`. Enabling code built+tested (`lib-money`, `lib-domain`, `revenue-ledger`); WBS WPs 3.2/3.3/4.8/5.5/7.3/10.3/15.3 annotated; **+12 backlog tickets** (3,432→3,444). See `Documentation/ADDENDUM-001-settlement-rounding.md` + `docs/MONEY_CONVENTION.md`. Live commit-path wiring pending (Phase 2).

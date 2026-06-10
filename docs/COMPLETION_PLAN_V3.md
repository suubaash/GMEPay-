# GMEPay+ — Completion Plan v3 (WBS re-baseline)

**Re-baselined:** 2026-06-10, immediately after the 22-agent code audit (`docs/WBS_STATUS.md`).
**Drives:** `GMEPay+_WBS.xlsx` → sheet **“Completion Plan v3”** (phase rollups) + 7 new status columns on the WBS sheet · `GMEPay+_Task_Backlog.xlsx` → per-ticket `Status`/`Evidence` + **55 new `-Gxx` tickets** · per-service bundles in `Documentation/services_backlog/`.

## Why a re-baseline

The audit showed the original phase plan was misleading us: services were *touched* broadly (16 backend modules + 2 UIs scaffolded green) but almost no ticket met its **acceptance check** — H2 instead of real PostgreSQL, stubs instead of live REST, log-publisher instead of Kafka, demo login instead of auth. The v3 plan stops organising work by *workstream* and organises it by **what unblocks what**, so agents always have a runnable next phase.

Sheet-level rollup after applying the audit to all 3,432 original tickets: **41 DONE · 412 PARTIAL · 2,979 NOT STARTED** (the audit-JSON headline 50/256/2,785 covered only the 3,091 tickets the audit agents returned; the sheet is now canonical). With the 55 new v3 tickets the backlog totals **3,487**.

## What changed in the WBS

1. **Every one of the 124 work packages** now carries audit columns (Tickets/Done/Partial/Not-Started/% Done/Status) and a **Completion Phase (R0–R8)** assignment.
2. **Three new workstreams** (20 work packages, 55 tickets):
   - **WS 17 — Real-Stack Gap Closure** (17.1–17.8): converts the PARTIAL cluster into DONE — Docker-capable CI, H2→PostgreSQL per service (12 tickets), Redis, Kafka + Schema Registry, Stub→REST flips, registry persistence, MongoDB merchant mirror, MinIO.
   - **WS 18 — Missing Components** (18.1–18.7): work the build *discovered* — ops-partner-bff legitimization (built ahead of tickets), Nginx WAF, SFTP gateway service, real authentication (replaces `password=demo`), UI contract-drift CI gates, docker-compose money-path E2E harness, and **ADR-001..005 stack decisions (needs user input)**.
   - **WS 20 — Phase-2+ Scheme Adapters** (20.1–20.5): KHQR/QPay/QRIS/SBP/PromptPay placeholders, expanded when partner contracts land.
3. **New owning services:** `ops-partner-bff` (legitimized) and `sftp-gateway` (new module) now have backlog bundles.

## The R-phases (dependency-true execution order)

| Phase | Name | Tickets (D/P/N) | Exit gate | Blockers |
|---|---|---|---|---|
| **R0** | Decisions & build environment | 190 (6/40/144) | Docker-capable CI green; ~~ADRs signed~~ **✅ decided 2026-06-10** (`docs/adr/`): Kafka+Schema Registry · Nginx layered over SCG · keep MongoDB · Rocky base images · ES=ELK logs | ~~user decision~~ cleared |
| **R1** | Persistence & real wiring | 424 (3/81/340) | 12 services on real PostgreSQL; Redis/Kafka/Mongo/MinIO wired; registries persistent | R0 |
| **R2** | Money-path E2E | 715 (20/131/564) | Scripted E2E quote→commit→settlement **incl. partner-rounding residual**→webhook green in CI | R1 |
| **R3** | Edge & security | 272 (8/24/240) | Nginx WAF; real JWT + RBAC; Vault; partner scoping enforced | R1 |
| **R4** | UI completion vs live BFF | 717 (0/80/637) | All admin + portal WBS screens functional; contract-drift CI gate | R2, R3 |
| **R5** | Infrastructure & observability | 268 (0/0/268) | Staging on K8s; OTel + Prometheus/Grafana + ELK + Jaeger; DR drill | GME cloud access |
| **R6** | Hardening, perf, pen test | 256 (1/14/241) | NFR-10 load targets; pen test passed | R2–R5 |
| **R7** | Compliance & certification 🔒 | 392 (3/41/348) | KFTC/ZeroPay certified; BOK FX1014/1015; Hometax | **KFTC test env · BOK OI-03 · Hometax OI-02** |
| **R8** | UAT, go-live & hypercare | 253 (0/1/252) | UAT signed; cutover rehearsed; hypercare; Phase-2 adapters start | R6, R7 |

R3 can run **in parallel with R2** (different services). R7 work that doesn't need the KFTC env (file formats, mappings, report capture) should be pulled forward whenever an agent is free.

## Critical path

```
ADRs (user) ─┐
             ├─ R0 Docker CI ── R1 real persistence ── R2 money-path E2E ──┐
             └────────────────── R3 auth/edge ─────────────────────────────┤
                                                                           ├─ R4 UIs ─ R6 hardening ─ R8 launch
   KFTC test env (external, ~mid-May slipped) ───── R7 certification ──────┘
```

The **single biggest unlock is 17.1 (Docker-capable CI)** — 412 PARTIAL tickets are partial *because* acceptance needed Testcontainers/compose. The **single biggest risk is R7** — entirely calendar-bound by KFTC/BOK/Hometax; everything code-side must be E2E-proven before that window opens.

## Immediate agent work queue (next 3 waves)

| Wave | Tickets | Services touched |
|---|---|---|
| 1 | 17.1-G01..03 (Docker CI + compose boots) · 18.7-G01 (ADR drafts → **user decides**) | platform-infra, program-mgmt |
| 2 | 17.2-G01..12 (H2→PostgreSQL ×12, parallel — one agent per service) · 17.3 Redis ×3 · 17.4 Kafka ×5 | all data services |
| 3 | 17.5 Stub→REST flips · 17.6 registry persistence · 18.6 E2E harness · 18.3 sftp-gateway · 18.4 real auth | executor, BFF, config-registry, qa, sftp-gateway, auth-identity |

Per the operating model: one agent per service, API-only communication, settings.gradle edits by coordinator only, every wave ends green + committed + pushed.

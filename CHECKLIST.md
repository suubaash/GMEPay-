# GMEPay+ — 100% Readiness Checklist (Plug-and-Play QR Payment Hub)

**Objective:** a partner (wallet/remit company) can *plug in once* — get keys, hit the sandbox,
go live — and their customers can pay any supported merchant QR across countries, with routing,
FX, funding, settlement and compliance handled invisibly and correctly.

**Legend:** `[x]` done/proven · `[~]` built but unproven / partial · `[ ]` not started · 🔒 blocked on external party
**Maintained by the readiness loop.** Last updated: 2026-07-04 (iteration 1).

> **Iteration 1 findings (2026-07-04):** (a) full Gradle build had ONE red module — api-gateway's
> context failed to load because the correlation-id auto-config referenced servlet types on the
> reactive stack; fixed by splitting servlet/reactive auto-configs and adding a reactive
> `CorrelationIdWebFilter` (gateway now propagates the id too). (b) Found 3 wire-contract defects
> in `RestSettlementClient` (exceptions path+shape, rerun request field names, rerun response
> shape) — the exact "nonce-class" bug family; fixed + pinned with tests. (c) BFF served stub
> data for 7 of 10 seams even in deploy; flipped revenue-ledger + settlement-reconciliation +
> ops-control to `rest` after contract verification. (d) `RestOpsControlClient` had 3 more wire
> defects — actor sent in body but read from `X-Actor` header (audit attribution lost),
> `maintenance` never sent `on:true` (would EXIT maintenance instead of entering), suspend sent
> `scope/ref` instead of `entityType/entityId` (suspended nothing) — all fixed + pinned with
> tests. Until the flip, the admin kill-switch only paused an in-memory stub, never the real
> `/v1/ops` gate payment-executor enforces. (e) payment-executor ran on in-memory H2 in compose —
> money-path state lost on restart; wired postgres-executor. Remaining on H2 in compose:
> qr-service, rate-fx, reporting-compliance, kyb-adapter. (f) partner-portal transactions-page
> tests were time-bombs (hardcoded dates aged out of the 30-day filter window on 2026-07-02);
> fixtures now relative to now — 9/9 green. (g) admin-ui: 778/779 green (1 flaky-under-load
> email-validation test, passes in isolation).
Companion docs: `MASTER_PLAN.md` · `docs/WBS_STATUS.md` · `docs/COMPLETION_PLAN_V3.md` · `outputs/audit_cpo_2026-07-03.md`

---

## 0. The definition of "works" (gate for every claim below)

- [~] **Golden-path gate per live corridor**: `WalletScanPayE2ETest` (scan → resolve → pay →
      APPROVED independently verified in transaction-mgmt + negative control) now runs as the
      blocking `e2e` CI job on every PR (iteration 3). *(CPO audit #1.)* The gate immediately
      caught a real regression: the fail-closed kill-switch declined 100% of payments because
      the fleet lacked config-registry — fixed by booting the real registry in the fleet.
      **Nepal corridor case added iteration 7** (classify → NPR → NEPAL adapter → sim →
      APPROVED, hub-authoritative currency asserted). Remaining for [x]: ledger/settlement
      tie-out legs in the same harness (webhook leg proven separately by
      PaymentApprovedWebhookDeliveryIT).
- [~] Sandbox E2E payment test runner exists (admin-ui E2E tab + payment-executor backend) —
      complements the CI gate for manual runs.
- [ ] "Done" for any money-path item = proven by an executed journey + tied to the cent, not code merged.

## 1. Flagship journey — scan → pay (per corridor)

- [~] **ZeroPay (KR domestic)**: MPM verified against simulator; CPM still a stub; H2/in-memory.
- [~] **Nepal (Fonepay)**: corridor built, 3 live defects found+fixed 2–3 Jul; **re-proven
      end-to-end iteration 7** — the golden-path CI gate now executes the full Fonepay journey
      (classify → NPR → adapter → sim → APPROVED) on every PR. Remaining: prove against the
      real partner rail (not sim) + CPM + refund.
- [x] GMEPay+ is authoritative for QR classification (corridor + currency decided by the hub).
- [~] QR-classified multi-partner failover routing (ADR-016) — code merged, needs E2E proof.
- [ ] CPM (customer-presented mode) flow implemented end-to-end.
- [ ] Refund/reversal journey exercised end-to-end (ledger + prefund release + partner webhook).
- [ ] Duplicate-scan / double-pay protection proven (idempotency exists on /v1/pay — prove it E2E).

## 2. Money correctness

- [x] Money convention (`docs/MONEY_CONVENTION.md`), Money/CurrencyScale lib, tests.
- [x] Double-entry revenue ledger with 70/30 split + reversing journals.
- [~] Rate engine (5-step, USD pool, 3-tier cascade) — real; **live rate feed not wired into quotes**.
- [~] Per-partner settlement rounding (ADDENDUM-001) — lib built; live commit-path wiring pending.
- [~] Prefunding atomic deduction (SELECT FOR UPDATE proven on PG integration test);
      float release on reversal fixed; low-balance alert **delivered** to a human? — unproven.
- [ ] Cent-for-cent tie-out report: payment vs ledger vs prefund vs settlement, automated daily.

## 3. Persistence & data (DB)

- [~] **PostgreSQL as the default runtime** for all 13 services currently defaulting to H2.
      Compose now covers 12 (config, txn, prefunding, ledger, settlement, notify, authid,
      scheme, executor — iteration 1; **qr-service, rate-fx, reporting-compliance —
      iteration 5**). kyb-adapter is not deployed in compose at all yet (its own gap).
      H2 remains only the local unit-test default, which is intended.
- [~] Flyway migrations exist per service (`db/migration`) — verified against H2, not all against PG.
- [ ] Restart-safety proven: kill any service mid-flight, txn state + outbox recover.
- [~] Simulators persist to JSONL (survive restart) — demo-grade, fine for sandbox.
- [ ] Mongo (merchant-qr-data) decision executed (keep/drop per ADR) + sync job real.
- [ ] Backups + restore drill for every store.

## 4. Eventing & integration

- [~] Outbox pattern in transaction-mgmt; events defined in lib-events/contracts.
- [ ] Kafka + Schema Registry as default bus (not log-stub); producer→Kafka→consumer→webhook green.
- [ ] Event contracts versioned + bound to schema registry; no terminal money transition can emit
      an event no consumer receives (CTO r2 gate).
- [~] `payment.reversed` contract added; reversal emits ledger signal (fix wave) — prove E2E.
- [~] `payment.approved` emitted and delivered as partner webhook. The chain is wired in
      compose (txn-mgmt outbox→Kafka + consumer + dispatcher enabled) and now PROVEN by ITs:
      outbox→Kafka (`OutboxKafkaPublishIT`) and Kafka→consume→enqueue→scheduled
      dispatch→HMAC-signed request→DELIVERED (`PaymentApprovedWebhookDeliveryIT`, iteration 4 —
      only the outbound socket is a double). Remaining for [x]: prove it in the running compose
      stack with a live receiver.

## 5. Partner plug-and-play (DX) — the "plug and play" promise

- [~] Partner self-service portal (txns, statements CSV/PDF, status trail) — real, data live-wired 3 Jul.
- [~] Sandbox API keys self-issued via auth-identity (Goal #5, merged 3 Jul) — prove the flow.
- [~] Self-serve onboarding (CPO gap fix e744c55) — verify wizard → live keys path.
- [ ] Developer portal: quickstart, API reference (OpenAPI published), copy-paste examples,
      Postman/SDK, sandbox simulators productised into onboarding.
- [ ] Webhook subscription self-service (register URL, rotate secret, replay from portal).
- [ ] Time-to-first-payment < 1 day measured in sandbox (activation-time metric).
- [ ] Versioned public API (/v1) + deprecation policy; contract-drift CI gate vs OpenAPI.

## 6. Security & auth

- [~] HMAC + idempotency filters at api-gateway (live-verified); internal auth filter; RBAC
      catalogue incl. scoped SUPPORT role.
- [~] Fail-closed kill-switch + fail-closed RBAC/audit in ops path (fix wave 61b0651).
- [ ] Real JWT/RBAC end-to-end — kill `password=demo`; issuance↔verify wired (Keycloak/OIDC).
- [ ] Vault (or equivalent) for secrets; no secrets in git/compose.
- [ ] Nginx/WAF edge, rate limiting/throttling (Redis) at gateway.
- [ ] Pen test passed; secret scanning + dependency audit in CI.

## 7. Operations & support

- [x] Ops console: control tower, kill-switch, alerts→on-call paging + ack, recon re-run,
      webhook replay, 360° txn search, force-resolve UNCERTAIN.
- [~] CS quick wins: decline reason, plain-language timeline, customer search, support-read BFF.
- [ ] Dispute/case management for partners (CPO #4) — today a stuck payment has nowhere to go.
- [ ] Partner-facing decline-reason transparency.
- [ ] Runbooks per alert; on-call rota; SLOs defined (success rate, latency, webhook delivery).

## 8. Settlement, reconciliation & scheme files

- [~] Net/gross calculators, line matcher, recon API, per-scheme statement + CSV (Goal #6).
- [ ] **Settlement batch lifecycle**: book/persist per-partner batches; "registration failure
      blocks settlement" enforced. *(Largest unbuilt MVP capability.)*
- [ ] Outbound ZeroPay files ZP0011/0021/0061/0063/0065/0066 generated + transmitted over SFTP
      (sftp-gateway service).
- [ ] Reconciliation running on real inbound files; breaks alert + operator workflow proven.

## 9. Compliance & regulatory 🔒 (calendar-bound, external)

- [ ] 🔒 KFTC/한결원 ZeroPay certification (gates ALL live domestic traffic — critical path).
- [ ] 🔒 BOK FX1014/1015 reporting format confirmed (OI-03) → verified submission.
- [ ] 🔒 Hometax tax-invoice API (OI-02) → merchant monthly billing.
- [~] AML (Octa partner) integration scoped; KoFIU/BOK code exists but gated off.
- [ ] Data-residency + PII handling reviewed.

## 10. Infra, observability & delivery

- [~] docker-compose + Helm charts exist (incl. Nepal corridor); Testcontainers ITs exist.
- [ ] CI: build + tests + golden-path E2E gate on every merge (Docker-capable runner).
- [ ] K8s staging environment continuously deployed; prod cluster + cutover plan.
- [ ] Observability: OTel traces, Prometheus/Grafana dashboards, ELK, alerting wired to paging.
- [x] Fleet-wide actuator probes, graceful shutdown, X-Correlation-Id end-to-end (error id == trace id).
- [ ] DR: backup/restore, failover drill, RTO/RPO stated.

## 11. Performance & resilience

- [~] Per-scheme circuit breaker + bulkhead + timeout on scheme edge; /v1/pay idempotency.
- [ ] Load test vs NFR-10 targets; soak test; chaos/resilience drills (kill scheme adapter mid-pay).
- [ ] Capacity plan + autoscaling policy.

## 12. Launch operations

- [ ] UAT signed off by GME Ops/Finance; GME Remit onboarded with real prefund.
- [ ] Rollback plan + go-live runbook rehearsed; 14-day hypercare staffed.
- [ ] Product funnel instrumented: per-partner/corridor success rate, decline trends,
      activation time (CPO #5).

---

## Milestone: what "50% complete" means for this loop

Weighted on the MVP-critical (money-path) slice — currently ~56% per the 2026-06-16 audit,
with post-audit work pushing toward ~60% *built* but far less *proven*. The loop counts an item
complete only when **proven** (test green / journey executed), and targets:

1. Full test suite green on every iteration (build never red).
2. Golden-path E2E (§0) runnable locally — the single highest-leverage unbuilt item.
3. PostgreSQL-default runtime for the money-path services (§3).
4. `payment.approved` emitted → webhook delivered (§4 — lights up the dark webhook stack).
5. Whole-txn data verified from UI/API level down to DB and back (amounts, FX, trail, ids).

When §0–§5 items above are green, the *proven* MVP slice crosses 50%.

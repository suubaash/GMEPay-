> 작업: QR hub maturity assessment / 출처: agent

# GMEPay+ — Payments-Platform Maturity Assessment

**Date:** 2026-07-03 · **Lens:** skeptical senior payments-platform engineer · **Read-only.**
**Scope:** `/d/GMEPay+/code` — 19 backend services, 1032 main Java files, 370 test files (~36% file ratio).

Distinct from the CPO audit (`outputs/audit_cpo_2026-07-03.md`, product-delivery lens). This is the **engineering-maturity** lens: does the plumbing survive next to a 10-year incumbent QR hub?

---

## VERDICT

**No — not yet.** The *money core* is genuinely strong (correct two-phase state machine, at-most-once irreversible submit, balanced double-entry, real recon diff engine, a booting golden-path e2e). But the **operational spine a 10-year hub takes for granted is thin or absent**: no metrics/tracing (Micrometer/Prometheus/OTel = **zero** in code), no circuit breakers (resilience4j = zero), single-node-only rate-limit/nonce/idempotency stores, in-app Flyway, and no implemented backup/DR. It is a **strong pilot-grade core wrapped in pre-production ops**. Ship-alongside only behind the incumbent, not as a peer.

---

## Maturity dimensions

| Dimension | Rating | One-clause basis (file checked) |
|---|---|---|
| **Exactly-once / idempotency (pay path)** | **MATURE** | `/authorize` unique `(partner,partner_txn_ref)` + `/confirm` atomic compare-and-set AUTHORIZED→CONFIRMING + UNCERTAIN-parking make the scheme submit at-most-once (`PaymentController.java`, `PaymentOrchestrator.confirmMpm`). **But** the `idempotency_keys` table (`V002`) + `IdempotencyRecordRepository` are **dead code — injected nowhere**; real dedup rides `partner_txn_ref`. |
| **Money integrity** | **MATURE** | Double-entry `Journal.post` rejects unbalanced-per-currency (`UnbalancedJournalException`); rounding residual booked (`V006`, `postRoundingResidual`); float hold=collectionUsd+serviceFeeUsd so nothing lands in a ledger void. |
| **Reliability under failure** | **PARTIAL** | Compensation on every failure branch + UNCERTAIN reconciliation path are real; timeouts set manually (`RestOperationalStatusClient`). **But resilience4j = 0 (no circuit breaker/bulkhead/retry lib)**; a slow scheme has no breaker. |
| **Observability** | **MISSING** | `micrometer-registry-prometheus` = **0 gradle files**; tracing/OTel = **0**; actuator in only **2/19** services. In-app event alerters exist (`DeclineSpikeMonitor`, `ReconBreakAlerter`, `OpsPagingEscalationScheduler`) but no metrics/trace/RED dashboards. |
| **API maturity** | **PARTIAL** | Gateway filter chain real: HMAC-SHA256 signing, `ReplayProtectionFilter` (X-Nonce), IP allowlist, RBAC stamp, `Idempotency-Key` **format check only**, versioned `/v1`. **But** rate-limit + nonce stores are **in-memory single-node** (`InMemoryRateLimitStore`, `InMemoryNonceStore`) — break/leak under horizontal scale. |
| **Data safety** | **MISSING** | `spring.flyway.enabled=true` → **migrations run in-app on boot** (not decoupled); backup/PITR/DR appear only in `services_backlog/*` docs, **no implemented DR**. |
| **Compliance/AML** | **PARTIAL** *(mostly GATED)* | KOFIU/CTR/STR aggregation real (`KofiuReportService`, `StrReport`) but wired to **Stub** feed/transaction ports (`StubKofiuFeedClient`); KYB has real Octa adapter + stub. **No customer sanctions/PEP/watchlist screening** (correct-ish: payer lives in partner app) — but no Travel-Rule/txn-monitoring engine either. Filing channels externally gated. |
| **Scale** | **PARTIAL** | ShedLock guards batch jobs (`transaction-mgmt/ShedLockConfig`, prefunding outbox); prefunding per-partner atomic lock is race-safe. **But** in-memory gateway stores + no load/soak validation cap real horizontal scaling. |
| **Testing** | **PARTIAL** | 370 test files; **one real golden-path e2e** booting the fleet as subprocesses (`e2e-tests/WalletScanPayE2ETest`) — the gap the CPO flagged, now partially closed. **But** e2e runs on H2/in-mem (no Docker/Kafka/Mongo → not prod-parity); **no contract tests** (Pact/Spring-Cloud-Contract absent) — the exact class of defect (nonce header-vs-body) that broke Nepal. |
| **Operability** | **PARTIAL** | Real kill-switch: `OpsControlService`/`OpsSuspensionEntity` enforced pre-side-effect via `OperationalGate.checkNewAuthorization`; recon rerun controller; ops paging. **But** no metrics-driven on-call, manual-recovery runbooks thin, UNCERTAIN auto-refund noted as follow-up. |

---

## Ranked gaps

1. **Observability blackout** — **[BUILDABLE-NOW]** no metrics/tracing/RED across 19 services. A 10-yr hub runs on SLO dashboards; here an outage is invisible until a decline-spike event fires.
2. **No circuit breakers / resilience lib** — **[BUILDABLE-NOW]** one slow scheme adapter can exhaust threads platform-wide with no bulkhead.
3. **Single-node gateway state** — **[BUILDABLE-NOW]** in-memory rate-limit + nonce ⇒ replay protection and throttling silently fail once you run >1 gateway instance (i.e. the moment you scale).
4. **No contract tests** — **[BUILDABLE-NOW]** the Nepal nonce break was a contract mismatch; nothing prevents the next one.
5. **In-app Flyway + no DR/PITR** — **[BUILDABLE-NOW code / GATED infra]** migration-on-boot risks a bad deploy locking the schema; no restore story.
6. **Compliance filing + txn-monitoring** — **[GATED]** KOFIU/BOK/Hometax channels + formats need gov/vendor; stubs are correct placeholders.
7. **Prod-parity e2e (Docker/Kafka/Mongo)** — **[BUILDABLE-NOW]** current e2e proves logic, not the real broker/DB cascade.

### Top 3 [BUILDABLE-NOW] — service + concrete build

1. **Observability baseline** — add `spring-boot-starter-actuator` + `micrometer-registry-prometheus` to **all 19 services** (shared-libs auto-config); expose `/actuator/prometheus` + liveness/readiness; add Micrometer `@Timed`/counters on `payment-executor` `/authorize`,`/confirm` and scheme-client calls (RED metrics per corridor). Wire OTel trace propagation through the gateway.
2. **Resilience on the scheme edge** — add **resilience4j** to `payment-executor` + `scheme-adapter-*`: circuit-breaker + timeout + bulkhead around every `SchemeClient` call (`RestSchemeClient`/`NepalRestSchemeClient`), so a dead scheme fast-fails to decline instead of hanging the pay path.
3. **Distributed gateway state** — replace `InMemoryRateLimitStore` + `InMemoryNonceStore` in **api-gateway** with a Redis-backed impl behind the existing `RateLimitStore`/`NonceStore` interfaces (already abstracted — swap-in only), making throttle + replay protection correct under N gateway replicas.

---

*Grounded in code as of 2026-07-03; ratings cite the specific file/table/migration checked. Money core = strong; ops spine = pre-production.*

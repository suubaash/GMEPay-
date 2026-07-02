# GMEPay+ — Target Features (Department-Audited)

**Date:** 1 July 2026
**Status:** Target feature set. The core platform (§B) is built and green in tests; the additions (§C) and the launch-readiness layer (§D) were surfaced by a department-head audit — Operations, Finance, Business Development, CTO, DevOps, Audit/Compliance, and CEO — which found **~104 gaps** the base spec did not cover.

---

## A. What GMEPay+ is
GME's cross-border QR-payment hub. It lets a wallet/remittance **partner's own customer pay a merchant by scanning a QR** — across borders and currencies — while GME handles routing, FX, funding, settlement, accounting, and regulatory reporting. GME earns an FX margin, a service fee to the partner, and a conditional share of the merchant fee. Actors: partners & their customers · QR schemes (ZeroPay/Korea, Khalti-Fonepay/Nepal, more later) · merchants · GME (the hub) · regulators (BOK, KoFIU, Hometax).

---

## B. Core platform features (built)

### Payments & QR
- **4.1 Inbound payment** (foreign customer → Korean merchant, ZeroPay) — two-phase authorize→confirm; only the partner charges its customer; GME submits to the scheme **last** (the only irreversible step).
- **4.2 Outbound payment** (customer → merchant abroad, e.g. Nepal) — single-phase, routed by the QR with multi-partner **failover** and a no-double-charge guard.
- **4.3 Scan & amount entry** — MPM static (customer types amount) / dynamic (amount in QR) / CPM (wallet-minted token with prefunding reservation).
- **4.4 Refund / cancel** — same-day reversal, mirror at the originally locked rate.

### FX & funding
- **4.5 FX quoting** — USD-intermediary two-leg calc, 3-tier rate cascade (scheme + GME margin + partner margin), locked ~15-min quote; GME bears FX risk in-window; same-currency skips FX.
- **4.6 Prefunding / float** — reserve→capture→release, top-up, low-balance alerts, credit limit; partner↔GME and GME↔scheme floats tracked separately.

### Settlement, revenue & accounting
- **4.7 Settlement & reconciliation** — net (domestic) / gross (overseas), match against scheme files, reconciliation exceptions, rounding residual → journal (once per batch), cross-date refund netting.
- **4.8 Revenue & accounting** — double-entry journal on payment-approved (FX margin + fee + configurable fee-share split) and on settlement.

### Partner & scheme management
- **4.9 Onboarding** — wizard → KYB → 4-eyes approval → credential issuance → webhook registration → credit limit → per partner×scheme×direction pricing (min-margin rule).
- **4.10 Scheme registration & routing** — config-driven, route-by-QR-network, multi-partner failover.
- **4.11 Merchant & QR data** — mirror + validate against a real active merchant (strict-mode rejects suspended/deactivated).

### Cross-cutting
- **4.12 Partner webhooks** — signed, retry/back-off, dead-letter queue.
- **4.13 Regulatory reporting** — BOK FX (FX1014/1015), KoFIU AML (CTR/STR), Hometax invoices *(aggregation built; live gov submission pending)*.
- **4.14 Security & access** — signed partner APIs (HMAC), replay protection, rate limits, OIDC operator login, RBAC, hash-chained audit.
- **4.15 Portals** — admin dashboards, onboarding/approvals, sandbox console, partner self-service.
- **4.16 Platform** — cloud-agnostic deploy (on-prem/AWS/Azure via config), one DB per service, reliable events (outbox→bus).

---

## C. Target additions (from the department audit)

### 4.17 Operations Control & Recovery *(Operations Head — 22 gaps)*
Live operations control tower (in-flight, UNCERTAIN, webhook backlog, float headroom, scheme health on one screen) · stuck/UNCERTAIN aging alerts + decline-spike anomaly detection · float burn-rate "time-to-empty" forecasting + maker-checker top-up workflow · scheme/partner connectivity health board · operator work-queue hub (assignable, SLA-tracked) · end-of-day / cutover console (across KST/NPT/USD) · manual-recovery toolkit (force-resolve UNCERTAIN, self-serve webhook replay, recon re-run — no DB edits) · emergency suspend/quarantine + global pause/maintenance mode · 360° transaction search & drill-down · case/ticket management + customer dispute/chargeback workflow + on-demand statement re-issue · SLA/service-health dashboard + on-call routing + runbook library · operator-action supervision/attestation · capacity/throughput dashboard · bulk ops & scheduled exports · guarded pricing/FX-margin change controls (maker-checker, effective-dating, impact preview).

### 4.18 Finance, Treasury & Accounting *(Finance Head — 17 gaps)*
GL/ERP export + full versioned chart of accounts · month-end close, per-currency trial balance, journal lock/no-backdating · **bank & nostro reconciliation** (real bank/SWIFT receipts vs expected settlement) + suspense/unidentified-funds account with aging · value-dating & settlement finality · treasury: consolidated multi-currency cash position, **FX open-position/exposure limits**, cross-currency funding forecast + hedging hooks, interest/cost-of-float · revenue recognition (earned vs settled, accrual/deferral, month-end true-up) + chargeback/refund provisioning · partner billing/statements of account + GME-issued invoices + credit notes · tax: VAT/GST on fees, cross-border withholding, tax-invoice completeness beyond Hometax · **profitability per partner × corridor × scheme** · management reporting (P&L, cash-flow, balance-sheet extract) · segregation-of-duties on all money movement + prefunding-adequacy/regulatory-capital monitoring.

### 4.19 Commercial & Growth *(Business Development — 22 gaps)*
**Developer portal + public API docs + client SDKs + self-serve sandbox keys/quickstart + published API versioning/deprecation policy** (parallel self-serve integration, not one-engineer-per-partner) · self-service trial front door + go-live certification checklist + BD pipeline/funnel visibility · flexible commercial pricing (tiered/volume/min-commitment, effective-dated promos, packaged plans) · corridor/scheme launch tracker + pre-commercial pilot mode · partner-facing growth analytics + decline-reason transparency · partner tiering + committed/published SLAs · partner CRM + health-scoring/QBRs + contract-lifecycle management · live coverage map / scheme marketplace · referral/incentive program · white-label + multi-language partner surface · merchant-side acquisition angle.

### 4.20 Architecture, Scale & Reliability *(CTO — 12 gaps)*
**Scheme-Adapter SDK + certification harness** (add a scheme as a plug-in — the scaling engine) · horizontal availability (multi-replica + HPA/PDB/anti-affinity + DB replicas; remove SPOFs) · disaster recovery (RPO/RTO, backup/restore runbooks + drills, multi-region) · event-schema governance (schema registry, versioning/compatibility, DLQ replay/ordering) · real distributed tracing + SLOs/error budgets · resilience patterns (circuit breakers, bulkheads, timeout/retry budgets, chaos testing) · prod-parity test environment + contract testing · data platform (warehouse/lake + CDC/ETL, retention/archival) · secrets & key management (KMS/HSM, rotation, PII-at-rest encryption/tokenization) · data-residency enforcement (Korea/Nepal) · NFR/load validation + hot-path tuning. *Cross-cutting warning: ~half of "done" is provisioned-but-not-plumbed — adopt a go-live gate separating "code exists" from "path exercised under load in prod-parity."*

### 4.21 Platform Infra, CI/CD & SRE Ops *(DevOps — 15 gaps)*
**Continuous delivery / GitOps** (biggest go-live blocker) · supply-chain security in CI (SAST, CVE/secret/image scan, SBOM, signing) · **safe DB migrations** (pre-upgrade Job, not in-app; checksum-drift guard) · zero-downtime deploy + one-click rollback · **metrics & dashboards deployed** (Prometheus/Grafana + Micrometer; golden signals) · alerting/paging/on-call/incident mgmt + status page · **batch-job safety** (distributed lock so multi-replica schedulers don't double-fire settlement/regulatory jobs; missed-run alerts) · IaC below the app chart + dev/stage/prod parity · **backup/PITR/restore automation + DR drills** · DB connection pooling (PgBouncer) + failover · K8s hardening (HA/HPA, NetworkPolicy, quotas, upgrade runbook) · secrets & TLS ops (rotation, cert-manager, expiry alerting) · env/config mgmt + feature-flag delivery + drift detection · central log aggregation + immutable (WORM) audit-log sink · edge WAF/rate-limit/DDoS + FinOps cost hooks.

### 4.22 Compliance, AML/CFT & Risk *(Audit/Compliance — 16 gaps)*
**Counterparty sanctions screening** (end-customers, merchants/beneficiaries, transfer counterparties vs OFAC/UN/EU/MOFA; daily rescreen) · **PEP screening** · **ongoing transaction monitoring** (tunable typology rules: structuring/velocity/round-tripping) · **alert → case-management → SAR/STR workflow** with filing clock · KYC/CDD/EDD tiers + risk-rating + periodic refresh · **cross-border Travel Rule** (originator/beneficiary info) · watchlist/blocklist management · **data-privacy program** (PIPA/Nepal/GDPR: consent, DSAR/erasure, cross-border-transfer legality) · retention & deletion schedule + legal hold · breach-notification workflow (PIPA 72-hour) · **licensing register + scope/conditions & limits monitor per country** · regulatory cumulative limits (per-customer daily/monthly/annual) · consumer disclosures/receipts + complaint-handling/dispute-rights register · **examiner evidence pack + control library / periodic control testing + independent audit-log export** · filing completeness & timeliness proof (actually submit + reconcile) · enterprise risk register + vendor/concentration risk + fraud & AML-rules governance + whistleblower/conflicts.

---

## D. CEO launch-readiness layer *(capstone)*
**Verdict:** a strong payments *engine*, not yet a payments *business*. **Not launchable today.** The single existential precondition before any real customer money moves: **licensed + a live sanctions/AML program AND a provably recoverable ledger** — the two ways the company *dies* (vs merely grows slowly).

**Strategy items (no single department owned):**
- **Launch-readiness scorecard** — rate every capability "code-exists vs proven-in-prod," not done/not-done.
- **Single accountable Go-Live Owner** with veto.
- **Phased country rollout** — one corridor, capped volume/ticket, regulator sign-off gating each expansion.
- **Unit-economics-before-scale gate** — profitability-per-corridor proven before funding the scaling engine.
- **Trust/brand + customer-harm plan**; **contingency / kill-switch / wind-down** protecting customer funds first.

---

## E. Go-live gate

### Must-do BEFORE go-live (non-negotiable)
1. **Licence + live AML/CFT** — sanctions/PEP screening, transaction monitoring, SAR workflow, Travel Rule, filing to a real regulator channel. *(4.22)*
2. **Irrecoverable-ledger fix** — backups/PITR + a rehearsed DR drill + migrations moved out of the app. *(4.21)*
3. **Close "provisioned-not-plumbed" on the critical path** — real KMS/secrets, live metrics+alerting+on-call, batch jobs multi-replica-safe. *(4.20/4.21)*
4. **Ops control + kill-switch** — control tower, float/stuck-txn alerts, global pause, manual recovery. *(4.17)*
5. **One clean real-money end-to-end** — inbound→FX→payout→settlement→recon→GL, tying to the cent in a prod-parity environment.

### Deliberately fast-follow (do NOT build before one corridor is proven profitable)
Finance depth (run semi-manual at pilot volume) *(4.18)* · the whole Business-Dev *(4.19)* and CTO *(4.20)* **scaling** engine.

---

_Sources: base End-to-End Feature Specification + per-department audits in `outputs/agent/audit_*_2026-07-01.md`. Living working copy: `outputs/FEATURE_LIST_AUDITED.md`._

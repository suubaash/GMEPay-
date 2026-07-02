# GMEPay+ — Feature List (Department-Audited, living)

> Base = the End-to-End Feature Specification. Each department head audits it in turn; gaps they find are folded in below. Progress tracked at the bottom.

## Base feature set (pre-audit)
**Sections:** (1) What GMEPay+ is · (2) Key concepts · (3) Microservices · (4) 16 end-to-end flows · (5) Money waterfall · (6) Coordination non-negotiables · (7) Phasing & external deps.

**The 16 flows:** 4.1 Inbound payment (2-phase ZeroPay) · 4.2 Outbound payment (Nepal, single-phase + failover) · 4.3 Scan & amount (static/dynamic/CPM) · 4.4 Refund/cancel · 4.5 FX quote (USD-intermediary, 3-tier cascade, TTL) · 4.6 Prefunding (reserve/capture/release, top-up, alerts, credit limit) · 4.7 Settlement & reconciliation (net/gross, match files, exceptions, rounding residual→journal) · 4.8 Revenue & accounting (journal on approved & settlement, config split) · 4.9 Onboarding (KYB→4-eyes→credentials→webhook→credit limit→pricing) · 4.10 Scheme registration & routing (config-driven, route-by-QR, failover) · 4.11 Merchant/QR sync & validation · 4.12 Partner webhooks (sign/retry/DLQ) · 4.13 Regulatory reporting (BOK/KoFIU/Hometax) · 4.14 Security & access (HMAC/replay/rate-limit/OIDC/RBAC/audit) · 4.15 Portals (admin/partner/sandbox) · 4.16 Platform (cloud-agnostic, 1-DB-per-service, events).

---
## Department Audit Log

### 1) Operations Head — 22 gaps → new group **4.17 Operations Control & Recovery**
- **Live control tower** — in-flight, UNCERTAIN, webhook backlog, float headroom, scheme health on one screen.
- **Stuck/UNCERTAIN aging alerts** + **decline/failure spike (anomaly) detection** per partner/scheme.
- **Float burn-rate "time-to-empty" forecasting** + **maker-checker top-up workflow** (request→approve→execute→confirm).
- **Scheme/partner connectivity health board** (up/down, latency, circuit state).
- **Operator work-queue hub** — one assignable, SLA-tracked inbox for exceptions/disputes/approvals.
- **End-of-day / cutover console** — guided, auditable close across KST/NPT/USD cutoffs.
- **Manual-recovery toolkit** — force-resolve UNCERTAIN (reason+audit, no DB edits), self-serve webhook replay, recon re-run.
- **Emergency controls** — suspend/quarantine partner/merchant/route/scheme; global pause / maintenance mode.
- **360° transaction search & drill-down** (full lifecycle/FX/journals/webhooks) — the #1 support tool.
- **Case/ticket management** + **customer dispute/chargeback workflow** + **on-demand statement re-issue**.
- **SLA/service-health dashboard + on-call routing + runbook library** linked to alerts.
- **Operator-action supervision/attestation UI**; **capacity/throughput dashboard**; **bulk ops & scheduled exports**; **guarded pricing/FX-margin change controls** (maker-checker, effective-dating, impact preview).

### 2) Finance / Treasury Head — 17 gaps → new group **4.18 Finance, Treasury & Accounting**
- **GL / ERP integration** — export balanced journals to the corporate accounting system on a versioned **chart of accounts** (today only REVENUE_ROUNDING is mapped).
- **Period close** — month-end close, per-currency **trial balance**, journal lock / no-backdating, close sign-off.
- **Bank & nostro reconciliation** — match actual bank/SWIFT receipts (esp. partner→GME SWIFT credit) to expected settlement + a **suspense / unidentified-funds account** with aging.
- **Value-dating & settlement finality** — book against when cash is actually good (drives interest, float cost, true "settled").
- **Treasury** — consolidated multi-currency cash position (KRW/USD/NPR); **FX open-position / exposure monitoring** + limits; cross-currency **funding forecast + hedging** hooks; **interest / cost-of-float** accrual.
- **Revenue recognition** — earned-vs-settled, accrued/deferred, month-end true-up (Phase-2 trading gain); **chargeback/refund provisioning** reserve.
- **Partner billing** — periodic per-partner **statement of account** (fees, margin, MDR share, netting), GME-issued invoices, credit notes / dispute flow.
- **Tax** — VAT/GST on GME fees, cross-border withholding tax, tax-invoice completeness beyond Hometax.
- **Profitability analytics** — margin per partner × corridor × scheme, net of FX/float/VAN/scheme-share/refund cost.
- **Management reporting** — consolidated P&L, cash-flow, balance-sheet extract (floats/receivables/payables).
- **Controls** — segregation-of-duties on all money-movement/journal actions; **prefunding-adequacy / regulatory-capital** monitoring (float ≥ obligations + buffer).

### 3) Business Development / Commercial — 22 gaps → new group **4.19 Commercial & Growth**
- **Developer portal & public API docs** + **self-serve sandbox API keys / quickstart** + **client SDKs (Node/Java/PHP)** + **published API versioning & deprecation policy** — parallel self-serve integration instead of one-engineer-per-partner.
- **Self-service trial front door** + **go-live certification checklist** + **BD pipeline / funnel visibility**.
- **Flexible commercial pricing** — tiered / volume / minimum-commitment, effective-dated promo/introductory rates, packaged rate-card plans (beyond a single margin).
- **Corridor / scheme launch tracker** + **pre-commercial pilot mode** (corridor expansion as a repeatable product, not a project).
- **Partner-facing growth analytics** (volume, decline reasons, top corridors) + **decline-reason transparency**.
- **Partner tiering + committed/published SLAs** (sales asset), **partner CRM + health-scoring / QBRs**, **contract-lifecycle management**.
- **Live coverage map / scheme marketplace**, **referral / incentive program**, **white-label + multi-language partner surface**, **merchant-side acquisition angle** (second growth flywheel).

### 4) CTO — 12 gaps → new group **4.20 Architecture, Scale & Reliability**
- **Scheme-Adapter SDK + certification harness** — add a scheme as a plug-in behind a common interface (no core recompile / bespoke service). The growth engine.
- **Horizontal availability** — multi-replica services + HPA/PDB/anti-affinity + DB replicas; remove single points of failure (Helm ships replicas=1 today).
- **Disaster recovery** — RPO/RTO targets, backup/restore runbooks + drills, multi-region.
- **Event-schema governance** — actually use the schema registry: versioned events + compatibility enforcement; DLQ replay + ordering guarantees.
- **Real distributed tracing + SLOs** — context propagation, correlation IDs, RED/USE metrics, error budgets & alerting (not a fire-and-forget tap).
- **Resilience patterns** — circuit breakers, bulkheads, timeout/retry budgets on scheme & inter-service calls; chaos testing.
- **Prod-parity test environment + contract testing** (Pact / Spring Cloud Contract) — kill the H2/mocks dev↔prod drift.
- **Data platform** — analytics warehouse/lake + CDC/ETL feeding Finance profitability & partner analytics; retention/archival.
- **Secrets & key management** — real KMS/HSM-backed vault, rotation, PII-at-rest encryption/tokenization (lib-vault is a doc store today).
- **Data-residency enforcement** — per-region data stores (Korea/Nepal sovereignty) — costly to retrofit.
- **NFR / load validation** — documented TPS/p99/availability targets + load testing + hot-path tuning (caching, pools, rate-quote store).
- **Progressive delivery + feature flags + FinOps + tech-debt register**. *(Cross-cutting: a go-live gate that separates "code exists" from "path exercised under load in prod-parity.")*
### 5) DevOps / SRE — 15 gaps → new group **4.21 Platform Infra, CI/CD & SRE Ops**  *(platform-infra was planned but not built — the app chart is good, the surround is undone)*
- **Continuous delivery / GitOps** — automated deploy (Argo/Flux) + a source-of-truth for what's running (today: helm upgrade from a laptop). Biggest go-live blocker.
- **Supply-chain security in CI** — SAST, dependency/CVE scan, secret scan, image scan + SBOM + signing.
- **Safe DB migrations** — run via a pre-upgrade Job (not in-app on boot) + CI checksum-drift guard (prevents the V022-type incident).
- **Zero-downtime deploy + rollback** — PodDisruptionBudget, canary/blue-green, one-click rollback runbook.
- **Metrics & dashboards deployed** — Prometheus/Grafana + Micrometer across services (golden signals); currently dark.
- **Alerting / paging / on-call / incident mgmt + status page** — someone gets woken when settlement fails at 02:00.
- **Batch-job safety** — distributed lock (ShedLock) so multi-replica schedulers don't double-fire settlement/regulatory jobs; missed-run alerts.
- **IaC below the app chart** — Terraform/Pulumi for DB/Kafka/Redis/DNS/TLS + dev/stage/prod parity.
- **Backup / PITR / restore automation + DR drills** — ~10 Postgres + Mongo have no backup story today.
- **DB connection pooling (PgBouncer) + failover**; **K8s hardening** (HA/HPA, NetworkPolicy, quotas, upgrade runbook).
- **Secrets & TLS ops** — rotation, cert-manager + expiry alerting (no CHANGE_ME placeholders in prod).
- **Env/config mgmt + feature-flag delivery + drift detection**; **central log aggregation + immutable (WORM) audit-log sink**; **edge WAF / rate-limit / DDoS + FinOps cost hooks**.

### 6) Audit / Compliance — 16 gaps → new group **4.22 Compliance, AML/CFT & Risk**  *(strong plumbing, but no AML/CFT program, no privacy program, not yet examinable)*
- **Counterparty sanctions screening** — screen end-customers, merchants/beneficiaries AND transfer counterparties (OFAC/UN/EU/MOFA) with daily rescreen — not just the partner entity at onboarding.
- **PEP screening** everywhere (currently none) — FATF/KoFIU-mandated.
- **Ongoing transaction monitoring** — tunable typology rules (structuring, velocity, round-tripping) feeding detection, beyond a single per-txn cap.
- **Alert → case-management → SAR/STR workflow** — alert queue, investigation case file, dispositions, filing clock (evidence why reports were/weren't filed).
- **KYC/CDD/EDD tiers + risk-rating + periodic refresh** (not one-shot PASS/FAIL).
- **Cross-border Travel Rule** — capture/transmit/hold originator & beneficiary info (FATF R.16).
- **Watchlist / blocklist management** — system of record + versioning; operationalize freeze orders.
- **Data-privacy program** (PIPA / Nepal / GDPR) — consent ledger, DSAR/erasure, cross-border-transfer legality.
- **Retention & deletion schedule + legal hold** — reconcile privacy-delete vs AML-retain.
- **Breach-notification workflow** — PIPA 72-hour detect→notify.
- **Licensing register + scope/conditions & limits monitor** per country — avoid unlicensed money transmission (existential).
- **Regulatory cumulative limits** — per-customer daily/monthly/annual ceilings (not just single-txn).
- **Consumer disclosures/receipts + complaint-handling/dispute-rights register** with SLA.
- **Examiner evidence pack + control library / periodic control testing + independent audit-log export**.
- **Filing completeness & timeliness proof** — actually submit + reconcile (filings sit GENERATED against a stubbed channel today).
- **Enterprise risk register + vendor/concentration risk + fraud-control & AML-rules governance + whistleblower/conflicts**.

### 7) CEO — capstone verdict & launch-readiness (strategy layer)
**Verdict:** a strong payments *engine*, not yet a payments *business*. Not launchable today. The single existential precondition before ANY real customer money moves: **licensed + a live sanctions/AML program AND a provably recoverable ledger** — the two ways this company *dies* (vs merely grows slowly).

**Top-5 go-live blockers (non-negotiable):**
1. **Licence + live AML/CFT** — real sanctions/PEP screening, transaction monitoring, SAR workflow, Travel Rule, filing to a *real* regulator channel.
2. **Irrecoverable-ledger fix** — backups/PITR + one rehearsed DR drill + migrations moved OUT of the app.
3. **Close "provisioned-not-plumbed" on the critical path** — real KMS/secrets, live metrics+alerting+on-call, batch jobs multi-replica-safe (see & stop the system).
4. **Ops control + kill-switch** — control tower, float/stuck-txn alerts, global pause, manual recovery.
5. **One clean real-money end-to-end** — inbound→FX→payout→settlement→recon→GL, tying to the cent in a prod-parity env.

**Deliberately fast-follow (do NOT build before one corridor is proven profitable):** Finance depth (run semi-manual at pilot volume) + the whole Business-Dev & CTO **scaling** engine.

**Strategy items no department owns (added):**
- **Launch-readiness scorecard** — rate every capability "code-exists vs proven-in-prod," not done/not-done.
- **Single accountable Go-Live Owner** with veto.
- **Phased country rollout** — one corridor, capped volume/ticket, regulator sign-off gating each expansion.
- **Unit-economics-before-scale gate** — profitability-per-corridor proven before funding the scaling engine.
- **Trust/brand + customer-harm plan**; **contingency / kill-switch / wind-down** that protects customer funds first.

---
## Progress
- [x] Operations Head — 22 gaps, group 4.17 added
- [x] Finance Head — 17 gaps, group 4.18 added
- [x] Business Development Head — 22 gaps, group 4.19 added
- [x] CTO — 12 gaps, group 4.20 added
- [x] DevOps — 15 gaps, group 4.21 added
- [x] Audit / Compliance — 16 gaps, group 4.22 added
- [x] CEO — capstone verdict + 5 go-live blockers + 5 strategy items

**LOOP COMPLETE — all 7 department heads. ~104 gaps surfaced; feature list rewritten with 6 new capability groups (4.17–4.22) + a CEO launch-readiness layer.**

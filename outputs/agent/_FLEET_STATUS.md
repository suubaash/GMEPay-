> 작업: 17 service-agents 병렬 백로그 완성 / 출처: agent
# GMEPay+ Fleet Status — 2026-06-30

Operating model: 1 agent = 1 service, isolated worktree at `D:/GMEPay+/wt/<svc>` on branch `agent/<svc>`.
Libs frozen (read-only); cross-service needs → INTEGRATION REQUEST in each agent's report.
Concurrency: waves of 5. Reports: `outputs/agent/build_<svc>_2026-06-30.md`.

## Wave plan
| Wave | Services | Status |
|---|---|---|
| 1 | payment-executor, transaction-mgmt, revenue-ledger, config-registry, notification-webhook | LAUNCHED |
| 2 | rate-fx, prefunding, scheme-adapter-zeropay, merchant-qr-data, qr-service | pending |
| 3 | api-gateway, auth-identity, ops-partner-bff, settlement-reconciliation, reporting-compliance | pending |
| 4 | smart-router, kyb-adapter | pending |
| post | admin-ui, partner-portal-ui, sftp-gateway, lib integration-requests | pending |

## Integration requests (collected from agent reports)
### lib-events (FROZEN — needs coordinated change)
- IR-txn-1: add `TransactionCommittedEvent` + `PrefundDeductedEvent` (snake_case, BigDecimal-as-string: collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, send_amount, committed_at). [from transaction-mgmt]
### lib-api-contracts (FROZEN)
- IR-txn-2: extend `TransactionCreateRequest` with rate-lock pool fields (offer_rate_coll, cross_rate, cost_rate_coll/pay, collection_usd, payout_usd_cost) for FX1015 persistence/re-emit. [from transaction-mgmt]
### cross-service API
- IR-txn-3: ✅ RESOLVED — prefunding now exposes atomic idempotent `POST /internal/v1/prefunding/{partnerId}/deduct` + `/reverse` returning balance+ledgerEntryId. transaction-mgmt must BIND this contract (deduct idempotencyKey == reverse txnRef; internal-network only). [prefunding done]
- IR-pe-2: STILL OPEN — prefunding `/deduct,/reverse` built but NOT `GET /v1/prefunding/{code}/deductions` (history). payment-executor `?include_history` blocked until added. → reconciliation pass.
- IR-pf-2: config-registry should push per-partner `credit_limit_usd` + AML caps (daily/monthly/annual + txn-count) to prefunding via `PUT .../credit-limit`; today arrive per-request. [prefunding → config-registry]
### contract-doc / decision
- IR-cfg-1: treasury-rate source-of-truth divergence — contract doc says config-registry owns /v1/treasury-rates but rate-fx actually owns rates (RateSnapshotEntity). DECISION: record rate-fx as owner, update INTER_SERVICE_CONTRACTS.md; do NOT build config-registry treasury endpoint. [from config-registry]

### lib-errors (FROZEN) — BATCH these enum additions in integration pass
- IR-pe-1: add `PAYMENT_NOT_FOUND(404,false)` + `FORBIDDEN(403,false)`. [payment-executor]
- IR-mqd-1: add `MERCHANT_SUSPENDED(422,false)` + `MERCHANT_DEACTIVATED(422,false)`. [merchant-qr-data]
- IR-sr-1: add `PAYMENT_MODE_NOT_SUPPORTED(409)` + `DIRECTION_NOT_ENABLED(409)`. [smart-router]
### cross-service API (route to prefunding agent)
- IR-pe-2: prefunding expose `GET /v1/prefunding/{code}/deductions?limit=N` (deduction history) so payment-executor balance `?include_history=true` works. [payment-executor → prefunding]
### integration-phase note
- IR-pe-3: payment-executor emits via `LogEventPublisher` (@ConditionalOnMissingBean); outbox→Kafka EventPublisher bean supersedes at integration. Seam ready. [from payment-executor]

### payment.approved EVENT CONTRACT (key integration item — converging from 3 agents)
- payment-executor must publish `gmepay.payment.approved` carrying: eventType, aggregateId/txnRef, occurredAt/revenueDate, partnerId, schemeId, collectionMarginUsd, payoutMarginUsd, serviceChargeAmount, serviceChargeCcy, feeSharePct (money=decimal strings). Consumed by revenue-ledger (capture) + notification-webhook (delivery). Define schema in lib-events/lib-api-contracts during integration. [revenue-ledger IR-1/2 + payment-executor IR-pe-3]
- GET /v1/revenue now returns `total_rounding_usd` → ops-bff + reporting revenue board add column. [revenue-ledger IR-3]

### prefunding follow-ups (batch into prefunding resume / reconciliation)
- IR-pe-2: `GET /v1/prefunding/{code}/deductions?limit=N` (history) [payment-executor]
- IR-qr-3: OVERSEAS `reserve()`/`release()` (SELECT FOR UPDATE) at CPM token issuance + release on expiry/decline; qr-service has nullable `prefund_reserved_usd` ready [qr-service]
### scheme-adapter-zeropay follow-up
- IR-qr-1: provide `PrepareTokenIssuancePort` bean (`schemePrepareTokenIssuancePort`) → real-time CPM prepare; throw SchemeUnavailableException(→422) [qr-service]
### smart-router follow-ups (fold into smart-router brief, Wave 4)
- IR-mqd-2: honor `gmepay.merchant.strict-mode` / stop synthesizing UNKNOWN merchant on lookup 404 [merchant-qr-data]
- IR-qr-2: data-driven scheme-for-location over partner_scheme tables (modes=CPM, direction enablement) [qr-service]

### transaction-mgmt COMMITTED-FX PROJECTION (2nd key integration theme — 4 consumers converged)
- transaction-mgmt must expose committed cross-border txn projection w/ rate-locked fields: txnRef, partnerId, direction, sameCcyShortcircuit, offerRateColl (=send_amount/(collection_usd−collection_margin_usd), FX1015 #14), crossRate (=target_payout/send_amount), collection/payout amounts+ccy, usdAmount, margins, committedAt. Canonical GET /v1/transactions omits margin-derived fields.
  - Consumers: reporting-compliance (FX1015), settlement-reconciliation (refund-date query + netting), scheme-adapter-zeropay (refund/fee/value-date enrichment), revenue-ledger (margins via payment.approved). Aligns w/ IR-txn-2 (TransactionCreateRequest pool fields). DECIDE the persisted columns + projection endpoint in integration pass.

## ⚠️ Meta-findings
- Published `services_backlog/*.md` + WBS_STATUS are STALE for config-registry (real pkg `com.gme.pay.registry`, V001–V034, ~95% done). Agents must assess ACTUAL code, not trust % in docs. Likely true for other mature services too.

## Per-service results
| Service | Build | Branch | Tickets | %est | Notes |
|---|---|---|---|---|---|
| transaction-mgmt | ✅ green | agent/transaction-mgmt @60dcabb | 7 | ~55-60 | SCHEME_SENT/UNCERTAIN FSM + real PATCH transition; remaining: 8-step event trail, prefund deduct (IR-txn-3), CPM orchestrator |
| config-registry | ✅ green | agent/config-registry @8d37963 | 1 | ~95 (already) | already mature; fixed scheme-catalog drift bug + test; backlog doc stale |
| notification-webhook | ✅ green | agent/notification-webhook | 1 | ~95 (already) | WebhookHttpClient+dispatcher+consumer already built; added DLQ ops-alert. Remaining: actuator metrics, DLQ admin API, CI docker ITs. No IR |
| payment-executor | ✅ green | agent/payment-executor | 3 | ~80-85 | orchestration already done; added GET payment/balance + lifecycle events. IR-pe-1/2/3. Remaining gated on Docker/prefunding-endpoint |
| revenue-ledger | ✅ green | agent/revenue-ledger | 2 | ~88 | added payment.approved consumer + rounding reporting. IR: payment.approved event contract. Remaining: fee-share query/admin API |
| prefunding | ✅ green | agent/prefunding | 2 | ~85 | built atomic idempotent /deduct + /reverse (resolves IR-txn-3). Remaining: recon/statements (6.5), admin top-up/threshold UI, BREACH auto-suspend |
| rate-fx | ✅ green | agent/rate-fx | 3 | ~90 | Partner-B quote source (4.6) + durable TTL + override endpoint. IR: config-registry rule rateSource fields, payment-executor commit-deviation guard, real Partner-B feed |
| merchant-qr-data | ✅ green | agent/merchant-qr-data @0afd4c9 | 4 | ~90 | strict-mode validation + transport port + orphan recon. IR: lib-errors merchant codes, smart-router honor strict-mode. Blocked: real SFTP/IDD layouts |
| qr-service | ✅ green | agent/qr-service @38daa9e | 9 | ~90 | real EMVCo CPM + prepare-token port + session persist. IR: scheme-adapter prepare-token, smart-router scheme-resolve, prefunding reserve/release |
| api-gateway | ✅ green | agent/api-gateway @1e07904 | 2 | ~90 | rate-limit filter + config credential source (replay already existed). IR: config-registry credential lookup, Redis stores |
| scheme-adapter-zeropay | ✅ green | agent/scheme-adapter-zeropay | 2 | ~85 | real batch data source (zp_committed_txns) + batch registry wiring. IR: txn-mgmt refund/fee/value-date enrichment. Blocked: real SFTP/PGP/IDD widths |
| auth-identity | ✅ green | agent/auth-identity @165b24b | 4 | ~90 | JWT issue/verify + DB credential lookup (resolves api-gateway IR-1) + key rotation. IR: secops HMAC-secret store decision (Vault). Blocked: Vault |
| settlement-reconciliation | ✅ green | agent/settlement-reconciliation @d66a34c | 5 | ~90 | recon tie-out fix + idempotency + batch lifecycle. IR: txn-mgmt refund-date query, revenue-ledger rounding contract. Blocked: real SFTP/IDD widths |
| smart-router | ✅ green | agent/smart-router | 2 | ~90 | data-driven scheme-for-location resolver + 4 branches (closes qr IR). IR: config-registry partner_scheme read contract, lib-errors codes, payment-executor lenient-merchant hardening |
| reporting-compliance | ✅ green | agent/reporting-compliance @a4a17b2 | 9 | ~90 | datastore + idempotent filings + FX1015 #14 populated. IR: txn-mgmt committed-FX projection. Blocked: OI-02/OI-03 gov channels |
| ops-partner-bff | ✅ green | agent/ops-partner-bff | 1 | ~85 | added Rest revenue+system-health clients (txn+settlement already existed). IR: revenue-ledger DTO/multi-axis, partner code→id map, settlement detail query. BIGGEST GAP: BFF mock login / no partner-scoping (R3 security) |
| kyb-adapter | ✅ green | agent/kyb-adapter @1199eb0 | 7 | ~90 | KYB screening orchestration + KFTC verify port + idempotent verify API. IR: config-registry call /v1/kyb/verify, kyb.verification topic consumers. Blocked: Octa/KFTC vendor creds |

**PHASE 1 COMPLETE: 17/17 services green on agent/* branches. ~48 tickets advanced.**
**MERGE: all 17 → `integration/fleet-2026-06-30` CLEAN (0 conflicts). Compile-verify running.**

## ⚠️ Phase 2 reconcile-at-merge items (path/shape mismatches found)
- PE↔prefunding RESERVE PATH: payment-executor client assumes `/reservations` POST/DELETE; prefunding built `/internal/v1/prefunding/{partnerId}/reserve` + `/release`. Shared DTOs match; align URL in payment-executor at merge. (qr-service was briefed with prefunding's real path.)
- PE event `schemeId=0` (orchestrator carries scheme CODE not numeric id) — numeric mapping follow-on.
- /refunded JSON shape: scheme-adapter (+settlement) coded their OWN wire DTOs for `GET /v1/transactions/refunded` (no lib type standardizes it). Field names (refundSchemeTxnRef/originalSchemeTxnRef/settlementDate) must match transaction-mgmt's actual projection — verify via integration test at merge. fx-committed uses shared CommittedFxView (safe).
- FX1015 margin accuracy: transaction-mgmt derives offerRateColl with zero margin (margins not on frozen commit PATCH). Margin-accurate FX1015 needs PATCH/TransactionCreateRequest extended w/ pool fields (deferred IR-txn-2).
- PE `reserveCpm/releaseCpm` bound+tested but not yet CALLED from executeCpm path — follow-on.

## Phase 3 — CLOUD-AGNOSTIC (AWS / Azure / on-prem)
Finding: platform was ALREADY provider-neutral (no AWS/Azure/GCP SDK; io.minio S3-API client; all endpoints env-injected; OTel/Keycloak/Kafka/S3 open protocols). Made it real + guaranteed:
- **cloud/audit** (003372e): lib-vault storage seam now full S3-compat — added GMEPAY_VAULT_REGION + GMEPAY_VAULT_PATH_STYLE (one client → MinIO/AWS S3/Azure-gateway). OIDC issuer env-driven (OIDC_ISSUER_URI, any provider). `portabilityGuard` Gradle task wired into `check` — FAILS build if com.amazonaws/software.amazon.awssdk/com.azure/com.google.cloud appears (negative-tested). Build green.
- **cloud/deploy** (8a28aee): `deploy/helm/gmepay/` umbrella chart — 1 _deployment.tpl over a services map → 18 Deployments+Services + Ingress + ConfigMap + Secret. Overlays values-{onprem,aws,azure}.yaml. helm lint PASS (helm 3.16.2), templates render for all 3. ADR-015 + docs/DEPLOYMENT.md (managed-service mapping table).
- **reconcile (991d29d)**: aligned chart vault cred env names to lib-vault's GMEPAY_VAULT_ACCESS_KEY/SECRET_KEY (parallel-agent drift).
Deploy ABI: same images everywhere; per-target = swap values-<target>.yaml. `helm upgrade --install gmepay deploy/helm/gmepay -f values-<target>.yaml -f my-secrets.yaml`.
Remaining: kyb-adapter needs a Dockerfile to deploy; managed Kafka/Mongo need SASL/TLS service-side props per target; lib-vault binds region/path-style (✅ done in audit) — chart provides them.
Live cluster provisioning (EKS/AKS) = ops step (needs cloud accounts). Terraform IaC NOT built (scope = portable-app-layer per user choice).

## Phase 2 STATUS: ✅ Step1 contracts (5dbafd5) + 11 services wired, ALL merged clean into integration/fleet-2026-06-30. Compile-verify running.
Phase-2 branches (all green per-service): p2/{transaction-mgmt,payment-executor,prefunding,revenue-ledger,reporting-compliance,notification-webhook,settlement-reconciliation,scheme-adapter-zeropay,qr-service,smart-router,merchant-qr-data}
Themes delivered: payment.approved event (PE emits → revenue-ledger + notification-webhook consume canonical type) · committed-FX projection (txn-mgmt /fx-committed + /refunded → reporting FX1015 #14, settlement netting, scheme-adapter enrichment) · lib-errors 6 codes flipped (PE, smart-router, merchant-qr-data) · prefunding deductions+reserve/release (→ payment-executor, qr-service).

## Phase 2 — original plan (for reference)
Ordered by leverage:
1. **lib-errors batch** (additive, safe): PAYMENT_NOT_FOUND, FORBIDDEN, MERCHANT_SUSPENDED, MERCHANT_DEACTIVATED, PAYMENT_MODE_NOT_SUPPORTED, DIRECTION_NOT_ENABLED → then flip the ~5 services off their String-literal workarounds.
2. **payment.approved event contract** (lib-events + lib-api-contracts): define schema → payment-executor emits (replace LogEventPublisher) → revenue-ledger + notification-webhook consume real events. Unlocks revenue capture + webhook delivery end-to-end.
3. **transaction-mgmt committed-FX projection**: persist rate-locked fields (offer_rate_coll, cross_rate, margins, collection/payout usd) + `GET /v1/transactions/fx-committed`. Consumers: reporting-compliance (FX1015), settlement-reconciliation (refund-date/netting), scheme-adapter (refund/fee/value-date). Biggest single unlock.
4. **prefunding** add `GET .../deductions` (history) + `reserve()/release()` (CPM). Binds transaction-mgmt + qr-service + payment-executor.
5. **config-registry read contracts**: partner_scheme location resolution (smart-router), rule rateSource fields (rate-fx), call /v1/kyb/verify (kyb-adapter), credential lookup already via auth-identity.
6. **DTO alignments**: revenue total_rounding_usd in RevenueSummaryResponse + multi-axis revenue endpoint + partner code→id map (ops-bff).
7. **R3 security**: replace BFF mock login w/ real JWT validation + partner-scoping (largest security gap).

External-blocked (NOT code, calendar/vendor): KFTC/ZeroPay SFTP+PGP+cert+IDD widths, BOK OI-03, Hometax OI-02, KoFIU channel, Octa KYB creds, Keycloak/Vault/real Postgres-Kafka-Redis infra.

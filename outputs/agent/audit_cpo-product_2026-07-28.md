> 작업: CPO product audit / 출처: agent (compiled by parent from two sub-audits: partner-onboarding surface + money-path completeness)

# CPO / Sales-readiness audit — GMEPay+ (2026-07-28)

Scope note: this report consolidates two evidence-gathering sub-audits (partner onboarding journey; money-path completeness). **Not covered here** (carry to next iteration): demoability/tunnel demo script, the 34 PRD use-case pass rate in `gmepay-test-platform`, partner-facing API/webhook documentation, SLA/pricing packaging collateral.

Severity: **BLOCKER-for-sale** = cannot honestly sell/onboard a paying partner · **MAJOR** = sellable only with heavy caveats/manual work · **MINOR** = polish.

---

## BLOCKER-for-sale

### P1. Go-live partner credentials are locally fabricated and authenticate nothing
`config-registry` never receives `GMEPAY_AUTH_IDENTITY_CLIENT=rest` (set only for `ops-partner-bff`: `docker-compose.yml:815`, `values.yaml:288`), so `StubAuthIdentityClient` wins in every environment: `issueKey()` returns `prefix + random`, `revokeKey()` is a no-op. The API key + HMAC secret an operator copies out of `ActivationCredentialModal.jsx` at activation cannot authenticate any call. Same defect for the webhook signing secret (`StubNotificationWebhookClient`, `matchIfMissing=true`) — partner signature verification is guaranteed to fail.
**Done =** both clients switched to `rest` in compose+Helm, activation issues a key that auth-identity verifies, and an E2E proves a partner call authenticating with an activation-issued key.

### P2. Partner portal login is unreachable in every environment
`oidc.js:31-32` defaults to realm `gmepay-partners`/client `gmepay-partner-ui` on :8090; compose runs Keycloak on **:8097** seeding realm `gmepay`/client `partner-portal-ui` (`realm-gmepay.json:70-77`); Helm points at the realm/client that exists in **no** seed file. Seeded client is confidential with a dev secret but `exchangeCode` sends no secret → `invalid_client`. `NEXT_PUBLIC_ALLOW_DEV_LOGIN` is set only in `vitest.setup.js`. The login page has no SSO button — only the deprecated `password=demo` form.
**Done =** one documented env where a partner user logs in via Keycloak without engineering help; realm/client/port aligned across oidc.js, compose, Helm, seed; dev-login removed from the partner surface.

### P3. `ops-partner-bff` has no authentication or tenancy enforcement, and bypasses the gateway
No `SecurityFilterChain`/filter/interceptor anywhere in `ops-partner-bff/src/main`; `X-Partner-Id` never read in its Java; `PartnerPortalController` takes `partnerId` from the path with no ownership check → cross-tenant read of balance/transactions/keys. `OpsRbacGuard` covers 6 controllers, **none** of the `Partner*Controller` onboarding endpoints (KYB screening, commission splits, activation are open writes). `GatewayRoutingConfig` has no route for `/v1/portal/**`, `/v1/admin/**`, `/v1/auth/**` — both UIs talk to :8095 directly, bypassing the OAuth2 resource server.
**Done =** BFF behind authN + partner-scoped authZ (JWT claim, not header), admin endpoints RBAC-guarded, traffic routed through the gateway. (Also CISO blockers #1–#3, #8.)

### P4. No partner self-serve journey exists, while the product presents one
All creation/config endpoints are under `/v1/admin/*` (`AdminDashboardController.java:194` etc.). No sign-up route/endpoint anywhere. The portal ships a "Partner Self-Service Portal" title, a `get-started` quickstart, and a nav of 8 pages whose footer says "Read-only Phase 1" — the only genuine self-serve write is sandbox key issuance. Missing entirely: KYB submission/status, document upload, fee/commission/pricing view, webhook create/edit, key rotate/revoke, settlement config.
**Done =** either (a) build sign-up + the Phase-2 writes, or (b) **product decision**: reposition the portal as read-only reporting and make onboarding explicitly operator-led in all collateral, removing self-serve affordances that 404.

### P5. Portal data a partner would look at is fixtures, with no real implementation path
`/webhooks` is hardcoded inline in the controller (`PartnerPortalController.java:168-183`, `partner.example.com` rows); `/profile` returns a constant `onboardedAt`; `StubApiKeyClient` serves two fake keys (no `RestApiKeyClient` exists); `StubStatementClient` emits 5 hardcoded CSV rows (no `RestStatementClient` exists); `GMEPAY_PREFUNDING_CLIENT` is never `rest`, so Overview/Balance 404 for real partners (`GMEREMIT`, `SENDMN`) and only serve `partner_test_001..003`.
**Done =** Rest clients for api-keys/statement/prefunding wired live; webhook + profile read from `notification-webhook`/`config-registry`; a real partner sees their own real data on every page.

### P6. KYB cannot be performed for real
`services/kyb-adapter` has **no Dockerfile**, is absent from `docker-compose.yml`, excluded from Helm (`deploy/CHANGELOG.md:39`). `GMEPAY_KYB_ADAPTER_CLIENT` unset → in-process `StubKybClient`. Decision logic is keyword matching (`StubKybAdapter.java:45-48`: name contains "SANCTIONED"→HIT, "REVIEW"→NEEDS_REVIEW, else CLEAR) and `StubKybVerifyClient` "never REJECTs" by its own javadoc. `OctaKybAdapter` throws `notYetAvailable()` (ADR-014, vendor sandbox pending). Document vault: `GMEPAY_VAULT_ENDPOINT` set in Helm but not compose → in-memory vault, uploaded KYB documents vanish on restart.
**Done =** kyb-adapter deployable (Dockerfile + compose + Helm), vault pointed at MinIO/real store, and either the vendor live or an explicit manual-KYB SOP that the product/compliance owner signs off as the interim control.

### P7. Nepal corridor sends KRW as NPR — no FX, no fee, no prefunding
`NepalPaymentService.java:36-43` (non-test): "the wallet amount is treated as NPR and passed straight through… No FX is applied here. TODO(fx)… the wallet-labeled KRW must not be sent as NPR in production." No prefunding deduct, no fee, no revenue booking on this path. Signing is a placeholder: `StubNepalSigner` returns constant `"c3R1Yi1zaWduYXR1cmU="` as the only `NepalRequestSigner` bean.
**Done =** Nepal path mirrors SENDMN (quote→FX→fee→prefund→scheme→ledger) with a real RSA signer, or the corridor is removed from the sellable catalog until it does.

### P8. Cross-border refunds misroute to ZeroPay (money-path defect)
`SchemeClientRouter.java:80-84`: `cancelPayment` carries no scheme code and unconditionally routes to the ZeroPay delegate. Nepal/SENDMN refunds therefore post their scheme refs to `/internal/scheme/zeropay/cancel`; the per-scheme clients that would correctly refuse (`NepalRestSchemeClient:108-114`, `SendmnRestSchemeClient:157-161`) are never reached, and neither cross-border adapter has a cancel endpoint. Failure surfaces as a ZeroPay decline, not `UNSUPPORTED`.
**Done =** router dispatches cancel by scheme; unsupported corridors return a clear `UNSUPPORTED` error; refund capability per corridor documented for sales.

### P9. Wallet `/v1/pay` path enforces no limits (all three corridors)
Limits config is regulatorily serious (`V020__partner_limits.sql` with a DB CHECK hard-capping `SOAEK_HAEOEMONG` at 5,000/50,000 USD; `V034` velocity count), and enforcement exists — but only on `POST /v1/payments/authorize`, and `chargeCumulative` is inside an `OVERSEAS`-only branch (`PaymentOrchestrator.java:252-276`). `WalletPayController` references only `OperationalGate`; the three wallet services call `prefundingClient.deduct` directly, which consults no caps. LOCAL partners have no cap enforcement at all.
**Done =** per-txn + daily/monthly/annual + velocity enforced on every payment entry point regardless of partner type, with tests proving a cap rejection on `/v1/pay`.

---

## MAJOR

### P10. Receipts/transaction detail never carry a merchant name
`TransactionResponse.java:172` `null, // merchantName — TODO: from scheme-adapter`; both BFF `buildDetail`s pass null; there is **no `merchant_name` column** in transaction-mgmt. SENDMN's adapter *does* obtain the name at verify-qr and it is dropped; SENDMN's lenient fallback synthesises "Unknown Merchant". Portal renders `—` always.
**Done =** merchant name persisted at payment time and rendered on receipt/detail for every corridor.

### P11. 9Pay payout has no hub orchestration (known, D4-deferred)
Adapter is production-shaped; nothing in the hub calls it — no `SchemeClientRouter` delegate, no `SchemeId`, no gateway route, no transaction-mgmt lifecycle, no partner-facing payout API; catalog is `PLANNED`. **Product decision required** (who initiates a payout, funding source, approval flow) before code.

### P12. Operating hours / scheme cutoffs enforced nowhere
`V024__scheme_operating_hours.sql` is seeded and readable (`GET /v1/schemes/{id}/operating-hours`) but grep finds zero consumers in payment-executor/smart-router/transaction-mgmt. `LocationSchemeResolver` has no time-window branch and no `SCHEME_CLOSED` error; availability is a manual operator flag (`OperationalGate`), which silently no-ops when `config-registry.base-url` is unset (`FixtureOperationalStatusClient.allClear()`).

### P13. Settlement never transmits, has no partner-facing statement, netting is dead code
`SettlementBatchStatus.java:17` — transmission out of scope; `ReconDiffEngine:286-289` fast-forwards TRANSMITTED→RECEIVED→RECONCILED as bookkeeping only; the only SFTP bean is `LocalDirSftpTransport` writing to a temp dir. `SettlementController` exposes one endpoint that recomputes from unbatched txns instead of reading persisted batches → `RestSettlementClient` returns null detail/404 and hardcodes `status=COMPLETED`. `MultilateralNettingCalculator` has no production caller. No settlement page in the portal; no funds-movement instruction anywhere.

### P14. No partial refunds; refund side-effects incomplete
Cancel/refund DTOs carry no amount; `refundAmountKrw` is never populated on the refund path, so settlement claw-back nets nothing (`SettlementBatchJobService:530`). `REFUNDED` emits no `payment.reversed` event → revenue reversal never runs. No refund/reversal consumer in `notification-webhook` → partners are never notified. `WalletPayController:539-540` posts a **zero** rounding-residual and patches status to `REVERSED` (not `REFUNDED`), so `findRefundedOn` misses it.

### P15. ZeroPay production edges are simulator/stub-shaped
`ZeroPaySchemeAdapter.java:67-69` documents the real-time path as implemented against `sim-scheme`; the TCP 전문 path ships QR detail fields 35-37 empty; SFTP is `LocalDirSftpTransport` ("no real SFTP connection is made… the only active implementation"); `ZpStubBatchDataPort` remains a live bean.
**Done =** real KFTC/ZeroPay endpoints + real SFTP behind config, with a certification run against the scheme.

---

## MINOR

### P16. Local dev/demo is misconfigured out of the box
`.env.local` sets `NEXT_PUBLIC_BFF_BASE_URL` which `next.config.mjs` explicitly warns flips the browser into cross-origin calls the BFF can't serve (zero CORS config in `ops-partner-bff`/`api-gateway`). Neither UI is a compose service. Get-Started's quickstart advertises `/v1/pay/classify` + `/v1/pay`, which the gateway doesn't route → a partner following the curl gets a 404 at the edge.

---

## Sales-readiness verdict

A partner cannot today: sign up, log in, submit KYB, receive working production credentials, rotate a key, configure a webhook, see real balances/statements, or be refunded on a cross-border payment. The **operator-driven admin wizard (steps 1–8) and commission-split configuration are genuinely built and are the strongest, most demoable assets** — sell from there, with onboarding positioned as white-glove/operator-led, and hold corridor claims to ZeroPay (+SENDMN with placeholder credentials) until P1–P9 close.

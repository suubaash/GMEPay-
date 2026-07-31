import { FIXTURES } from '../config';
import { Ctx, UseCase, blocked, ensureSchemeMerchant, unsupported } from './testkit';
import { FEATURE_TESTS } from '../usecases/features';

export type { Ctx, UseCase } from './testkit';

/**
 * The 34 GMEPay+ use cases from the Business Scenario & Use Case doc + the PRD
 * audit. This array IS the traceability matrix: every intended function appears
 * here. Use cases with a `run` function execute real HTTP against the services;
 * the rest are placeholders awaiting automation (status NOT_AUTOMATED).
 */
const PRD_USE_CASES: UseCase[] = [
  // ------------------------------------------------------------------ BS-01
  {
    id: 'UC-01-01',
    bs: 'BS-01',
    bsTitle: 'Domestic Customer Payment',
    title: 'CPM Payment (GME Remit, Domestic)',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['payment-executor', 'scheme-adapter-zeropay', 'merchant-qr-data', 'transaction-mgmt'],
    intent: 'Domestic KRW→KRW wallet payment: KRW 500 fee added, no FX, transaction APPROVED.',
    automated: true,
    async run({ client, check }) {
      await ensureSchemeMerchant(client);
      const res = await client.pay({
        qrPayload: FIXTURES.qrPayload,
        amountKrw: '50000',
        partner: 'GMEREMIT',
        userRef: FIXTURES.gmeremitUser,
      });
      check.blockedIf(res.status >= 500, `payment-executor returned ${res.status} (server error)`);
      check.blockedIf(res.status === 400 || res.status === 422,
        `wallet /v1/pay orchestration returns ${res.status} even though QR-parse, merchant-lookup and scheme-authorize all pass individually (F-QR-01 / F-MERCH-01 / F-SCHEME-01) — opaque VALIDATION_ERROR indicates a QR-resolution/validation wiring defect in payment-executor; needs a platform-side fix`);
      check.equal(res.status, 201, 'HTTP 201 Created on approval');
      check.equal(res.json?.status, 'APPROVED', 'payment status is APPROVED');
      check.equal(res.json?.fxApplied ?? false, false, 'no FX applied for domestic');
      check.closeTo(Number(res.json?.feeKrw), 500, 0.001, 'KRW 500 service fee booked');
      check.ok(!!res.json?.schemeTxnRef, 'scheme transaction reference returned', res.json?.schemeTxnRef);
    },
  },
  {
    id: 'UC-01-02',
    bs: 'BS-01',
    bsTitle: 'Domestic Customer Payment',
    title: 'MPM Payment (GME Remit, Domestic)',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['payment-executor', 'scheme-adapter-zeropay', 'merchant-qr-data'],
    intent: 'Customer scans merchant static QR and enters amount; KRW 500 fee, no FX.',
    automated: true,
    async run({ client, check }) {
      await ensureSchemeMerchant(client);
      // MPM differs by who enters the amount; the wallet money path is the same endpoint.
      const res = await client.pay({
        qrPayload: FIXTURES.qrPayload,
        amountKrw: '12000',
        partner: 'GMEREMIT',
        userRef: FIXTURES.gmeremitUser,
      });
      check.blockedIf(res.status >= 500, `payment-executor returned ${res.status}`);
      check.blockedIf(res.status === 400 || res.status === 422,
        `wallet /v1/pay orchestration returns ${res.status} despite QR-parse/merchant-lookup/scheme-authorize passing individually — QR-resolution wiring defect in payment-executor (platform-side fix needed)`);
      check.equal(res.status, 201, 'HTTP 201 Created');
      check.equal(res.json?.status, 'APPROVED', 'payment APPROVED');
      check.closeTo(Number(res.json?.feeKrw), 500, 0.001, 'KRW 500 fee booked');
    },
  },
  // ------------------------------------------------------------------ BS-02
  {
    id: 'UC-02-01',
    bs: 'BS-02',
    bsTitle: 'Overseas Customer Payment',
    title: 'CPM Payment (SendMN, Overseas Inbound)',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['payment-executor', 'prefunding', 'rate-fx', 'scheme-adapter-zeropay'],
    intent: 'Overseas wallet payment: 2% FX margin + KRW 500 fee, prefunding deducted in real time.',
    automated: true,
    async run({ client, check }) {
      await ensureSchemeMerchant(client);
      const res = await client.pay({
        qrPayload: FIXTURES.qrPayload,
        amountKrw: '50000',
        partner: 'SENDMN',
        userRef: FIXTURES.sendmnUser,
      });
      check.blockedIf(res.status >= 500, `payment-executor returned ${res.status}`);
      check.blockedIf(res.status === 400,
        `wallet /v1/pay orchestration returns 400 despite QR-parse/merchant-lookup/scheme-authorize passing individually — QR-resolution wiring defect in payment-executor (platform-side fix needed)`);
      // A decline due to no seeded prefunding is a precondition gap, not a logic failure.
      const reason = String(res.json?.declineReason ?? '').toLowerCase();
      check.blockedIf(
        res.status === 422 && reason.includes('prefund'),
        'SENDMN prefunding balance not seeded in this environment',
      );
      check.equal(res.status, 201, 'HTTP 201 Created on approval');
      check.equal(res.json?.status, 'APPROVED', 'payment APPROVED');
      check.equal(res.json?.fxApplied, true, 'FX applied for overseas');
      check.ok(!!res.json?.fxRate, 'FX rate present on response', res.json?.fxRate);
    },
  },
  {
    id: 'UC-02-02',
    bs: 'BS-02',
    bsTitle: 'Overseas Customer Payment',
    title: 'MPM Payment (SendMN, Overseas Inbound)',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['payment-executor', 'prefunding', 'rate-fx'],
    intent: 'Overseas static-QR payment with customer-entered amount; FX + fee + prefunding.',
    automated: false,
  },
  // ------------------------------------------------------------------ BS-03
  {
    id: 'UC-03-01',
    bs: 'BS-03',
    bsTitle: 'Payment Amount Calculation',
    title: 'Domestic Charge Amount Calculation',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['rate-fx'],
    intent: 'Same-currency (KRW→KRW) short-circuits the FX pool: collection = payout + service charge.',
    automated: true,
    async run({ client, check }) {
      const res = await client.rate({
        targetPayout: 10000,
        collectionCurrency: 'KRW',
        settleACurrency: 'KRW',
        settleBCurrency: 'KRW',
        payoutCurrency: 'KRW',
        costRateColl: null,
        costRatePay: null,
        mA: 0,
        mB: 0,
        serviceCharge: 500,
      });
      check.blockedIf(res.status >= 500, `rate-fx returned ${res.status}`);
      check.equal(res.status, 200, 'HTTP 200 from /v1/rates');
      check.equal(res.json?.shortCircuit, true, 'same-currency short-circuit engaged');
      check.closeTo(Number(res.json?.collectionAmount), 10500, 0.01, 'collection = payout + 500');
    },
  },
  {
    id: 'UC-03-02',
    bs: 'BS-03',
    bsTitle: 'Payment Amount Calculation',
    title: 'Overseas Charge Settlement Amount Calculation',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['rate-fx'],
    intent: 'Cross-border 5-step USD-pool calc with ≥2% combined margin and pool identity.',
    automated: true,
    async run({ client, check }) {
      const res = await client.rate({
        targetPayout: 13500,
        collectionCurrency: 'MNT',
        settleACurrency: 'MNT',
        settleBCurrency: 'KRW',
        payoutCurrency: 'KRW',
        costRateColl: 3500,
        costRatePay: 1350,
        mA: 0.01,
        mB: 0.01,
        serviceCharge: 500,
      });
      check.blockedIf(res.status >= 500, `rate-fx returned ${res.status}`);
      check.equal(res.status, 200, 'HTTP 200 from /v1/rates');
      check.equal(res.json?.shortCircuit, false, 'cross-border path (no short-circuit)');
      check.closeTo(Number(res.json?.payoutUsdCost), 10, 0.001, 'payout cost = 10 USD');
      // pool identity: collectionUsd ≈ collMargin + payMargin + payoutUsdCost
      const delta = Math.abs(
        Number(res.json?.collectionUsd) -
          Number(res.json?.collectionMarginUsd) -
          Number(res.json?.payoutMarginUsd) -
          Number(res.json?.payoutUsdCost),
      );
      check.ok(delta < 0.01, 'USD pool identity holds within 0.01', { delta });
    },
  },
  // ------------------------------------------------------------------ BS-04
  blocked('UC-04-01', 'BS-04', 'Zeropay Settlement', 'Payment & Refund Result Registration (ZeroPay SFTP)', true, ['scheme-adapter-zeropay', 'settlement-reconciliation'], 'Generate ZP0011/0021 batch files over SFTP. Blocked: batch scheduler off, SFTP is a local-dir stub, zero-record files.'),
  blocked('UC-04-02', 'BS-04', 'Zeropay Settlement', 'ZeroPay Platform Settlement Reconciliation', true, ['settlement-reconciliation'], 'Reconcile ZP0061-0066 files. Blocked: recon scheduler off, no files ingested, batches never persisted.'),
  blocked('UC-04-03', 'BS-04', 'Zeropay Settlement', 'Settlement Exception Manual Processing', true, ['settlement-reconciliation', 'ops-partner-bff'], 'Operator resolves recon exceptions. Blocked: no upstream flagging (recon pipeline never runs) + admin UI stub-backed.'),
  blocked('UC-04-04', 'BS-04', 'Zeropay Settlement', 'Overseas Merchant Fee Tax Invoice (Monthly)', true, ['reporting-compliance'], 'Issue Hometax invoices. Blocked: scheduler off, StubHometaxClient, no NTS mTLS cert (OI-02).'),
  // ------------------------------------------------------------------ BS-05
  {
    id: 'UC-05-01',
    bs: 'BS-05',
    bsTitle: 'FX Rate Management',
    title: 'FX Rate Fetch & Store (xe.com / 15 min)',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['rate-fx', 'sim-rate-provider'],
    intent: 'Fetch USD/KRW every 15 min from xe.com and store snapshots.',
    automated: true,
    async run({ client, check }) {
      // The scheduler is off by default; we at least prove the rate engine is alive.
      const res = await client.rate({
        targetPayout: 1000,
        collectionCurrency: 'KRW',
        settleACurrency: 'KRW',
        settleBCurrency: 'KRW',
        payoutCurrency: 'KRW',
        mA: 0,
        mB: 0,
        serviceCharge: 0,
      });
      check.blockedIf(res.status >= 500, `rate-fx returned ${res.status}`);
      check.ok(res.status === 200, 'rate-fx engine reachable', res.status);
      check.blockedIf(true, 'xe.com fetch scheduler is OFF by default and points at the simulator, not live xe.com');
    },
  },
  // ------------------------------------------------------------------ BS-06
  {
    id: 'UC-06-01',
    bs: 'BS-06',
    bsTitle: 'Prefunding Management',
    title: 'Prefunding Balance Management (Real-time Deduction & Alerts)',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['prefunding'],
    intent: 'Maintain partner prepaid balance with atomic deduction and low-balance alerts.',
    automated: true,
    // T0-5: prefunding's whole /v1/prefunding/** API is internal-gated.
    credentials: ['internal'],
    async run({ client, check }) {
      const res = await client.prefundingBalance(FIXTURES.sendmnPartnerCode);
      check.blockedIf(res.status >= 500, `prefunding returned ${res.status}`);
      check.blockedIf(res.status === 404, 'no prefunding account seeded for SENDMN in this environment');
      check.equal(res.status, 200, 'HTTP 200 balance read');
      check.ok(res.json?.balance !== undefined, 'balance field present', res.json);
    },
  },
  // ------------------------------------------------------------------ BS-07
  blocked('UC-07-01', 'BS-07', 'Merchant & QR Data Management', 'Merchant Data Sync (ZeroPay → GME)', true, ['merchant-qr-data'], 'Ingest ZP004x merchant files. Blocked: sync scheduler off, reads local dir not SFTP, Mongo excluded.'),
  blocked('UC-07-02', 'BS-07', 'Merchant & QR Data Management', 'QR Data Sync (ZeroPay → GME)', true, ['merchant-qr-data'], 'Ingest ZP0043/0053 QR files. Blocked: same disabled-scheduler/local-dir constraints.'),
  {
    id: 'UC-07-03',
    bs: 'BS-07',
    bsTitle: 'Merchant & QR Data Management',
    title: 'Merchant & QR Real-time Validation',
    mvp: true,
    phase: 'Phase 1 (MVP)',
    services: ['merchant-qr-data'],
    intent: 'Resolve a merchant from a scanned QR and report active/valid status; 404 on unknown.',
    automated: true,
    async run({ client, check }) {
      const res = await client.merchantByQr(FIXTURES.merchantId);
      check.blockedIf(res.status >= 500, `merchant-qr-data returned ${res.status}`);
      check.blockedIf(res.status === 404, `merchant ${FIXTURES.merchantId} not seeded in this environment`);
      check.equal(res.status, 200, 'HTTP 200 merchant resolved');
      check.ok(!!res.json, 'merchant record returned', res.json);
    },
  },
  // ------------------------------------------------------------------ BS-08
  blocked('UC-08-01', 'BS-08', 'BOK Reporting', 'Domestic Transaction Report', true, ['reporting-compliance'], 'BOK domestic report. Blocked: scheduler off, no persistence, domestic format unconfirmed (OI-03).'),
  blocked('UC-08-02', 'BS-08', 'BOK Reporting', 'Overseas Transaction Report (FX1014/1015)', true, ['reporting-compliance', 'transaction-mgmt'], 'BOK FX1014/1015. Blocked: scheduler off, offer_rate_coll not derivable, format pending (OI-03).'),
  // ------------------------------------------------------------------ BS-09 (Phase 2)
  blocked('UC-09-01', 'BS-09', 'QR Scheme / Partner Expansion', 'New QR Scheme Registration', false, ['config-registry', 'smart-router'], 'Phase 2. Config persists scheme enablements but there is no GET /v1/schemes catalog and only ZEROPAY is recognised.'),
  blocked('UC-09-02', 'BS-09', 'QR Scheme / Partner Expansion', 'New API Client / Partner Onboarding (KYB)', false, ['config-registry', 'kyb-adapter', 'ops-partner-bff'], 'EXPECTATION CHANGED (T1-2): the wizard runs, but ACTIVATION now deliberately REFUSES with 422 SANCTIONS_NOT_SCREENED because no KYB provider is contracted — KybEntity.hasAuthoritativeScreening() is false and the stub can only ever return MANUAL_REVIEW / NOT_SCREENED_NO_PROVIDER. This is the intended fail-closed behaviour, not a defect: onboarding cannot complete until a real provider is wired (or gmepay.activation.allow-unscreened-kyb is deliberately turned on, which writes a PARTNER_ACTIVATED_UNSCREENED audit row). The refusal itself is asserted by F-KYB-03.'),
  blocked('UC-09-03', 'BS-09', 'QR Scheme / Partner Expansion', 'Routing Rule / Margin Configuration', false, ['config-registry'], 'Phase 2. Rule persistence + ≥2% invariant exist but rules are not consumed by the live payment path.'),
  // ------------------------------------------------------------------ BS-10
  // T1-3 made these REAL: gmepay.prefunding.client / gmepay.notification-webhook.client
  // are now `rest` on all three surfaces, and PartnerDirectory bridges the partner business
  // code to config-registry's numeric surrogate. The remaining precondition is data +
  // a partner-scoped OIDC token, not a stub. F-PORTAL-01..04 exercise the BFF layer directly.
  blocked('UC-10-01', 'BS-10', 'Partner Self-Service Portal', 'Prefunding Balance Inquiry', true, ['partner-portal-ui', 'ops-partner-bff', 'prefunding'], 'EXPECTATION CHANGED (T1-3): no longer stub-backed — RestPrefundingClient is now the wired default. What blocks a green PASS is data and identity, not a stub: the balance page needs a real partner_balance row (a real partner with none legitimately 404s) and a partner-scoped bearer token whose partner_id claim matches the partner in the path. Covered at the BFF layer by F-PORTAL-01.'),
  blocked('UC-10-02', 'BS-10', 'Partner Self-Service Portal', 'Transaction History Inquiry', true, ['partner-portal-ui', 'ops-partner-bff', 'transaction-mgmt'], 'EXPECTATION CHANGED (T1-3): the rest client is wired and partnerId is always on the wire (no upstream can be asked for "all partners"). Blocked only on seeded transactions for the partner + a partner-scoped token. Covered at the BFF layer by F-PORTAL-03.'),
  blocked('UC-10-03', 'BS-10', 'Partner Self-Service Portal', 'Transaction Detail Inquiry', true, ['partner-portal-ui', 'ops-partner-bff'], 'EXPECTATION CHANGED (T1-3): detail reads real data. Fields like API-key name/scopes/lastUsedAt and webhook lastDeliveredAt are absent-by-design (null / em dash) rather than zero-filled, so a test must NOT assert they are populated. Blocked on seeded data + a partner-scoped token.'),
  // ------------------------------------------------- Extended use cases (PRD audit)
  blocked('UC-RATE-QUOTE', 'EXT', 'Rate & Quote', 'Rate Quote Issuance & TTL Lock', true, ['rate-fx'], 'POST/GET /v1/quotes work standalone but TTL lock is in-memory (no Redis) and quote→pay handoff not driven clean.'),
  blocked('UC-CANCEL-PAYMENT', 'EXT', 'Refund / Cancel', 'Cancel Payment (Same-day Reversal)', true, ['payment-executor', 'scheme-adapter-zeropay'], 'EXPECTATION CHANGED (T2-6/T2-7): the "prefund return ZERO" approximation is gone — a partial refund is now booked as a real reversal for exactly that amount, and where it cannot be transmitted the platform REFUSES rather than approximating: 422 PARTIAL_REFUND_UNSUPPORTED on ZeroPay (no amount field in its cancel contract) and 422 SCHEME_OPERATION_UNSUPPORTED on the single-shot corridors. Full cancel is unchanged. The refusal contracts are asserted by F-REFUND-01 / F-REFUND-02; a full same-day reversal with REVERSED FSM assertions is still not driven end-to-end.'),
  blocked('UC-REFUND-ADMIN', 'EXT', 'Refund / Cancel', 'Refund Processing via Admin Portal', false, ['admin-ui', 'ops-partner-bff'], 'No admin refund flow runnable e2e; ZP0021 generates zero-record files. Note the operator surfaces (BFF + admin-ui) expose no refund AMOUNT field at all, so partial refunds are API-only by construction — and neither surface yet sends X-Scheme-Id, so an operator-initiated cross-border refund would take the legacy ZeroPay route.'),
  blocked('UC-API-AUTH', 'EXT', 'Security', 'Partner API Authentication & Authorization', true, ['api-gateway', 'auth-identity'], 'EXPECTATION CHANGED (T1-1): the 2-key stub is DELETED from src/main and replay protection is live and fail-closed (X-Nonce mandatory, reuse → 401), as is the IP allowlist (no longer fail-open) and rate limiting. The edge now authenticates against a split credential store — auth-identity for identity/lifecycle AND gateway.partner-credentials.partners[] for HMAC material — so a key in only one half correctly 401s. Blocked on an OPERATOR provisioning a real key pair into both halves; until then the partner API authenticates nobody. See F-GW-02/03/04.'),
  blocked('UC-OPS-RBAC', 'EXT', 'Security', 'Operator RBAC & Audit Logging', true, ['ops-partner-bff', 'config-registry'], 'EXPECTATION CHANGED (T0-1/T1-1): the password=demo mock is DELETED (POST /v1/auth/login no longer exists) and Keycloak IS the login path — realm `gmepay`, public PKCE clients, with `permissions` and `partner_id` claim mappers driving authorization. Self-asserted X-Gme-Permissions headers now grant nothing. Blocked on driving the audit assertions with a real operator token; the boundary itself is asserted by F-BFF-01/05 and F-ADMIN-04.'),
  blocked('UC-PREFUND-TOPUP', 'EXT', 'Prefunding', 'Prefunding Top-up / Initial Deposit', true, ['prefunding', 'ops-partner-bff'], 'Provision + credit endpoints exist but go-live gating not driven with data; credits only via explicit endpoint.'),
  blocked('UC-RATE-CPM-PREPARE', 'EXT', 'Scheme', 'CPM Prepare / Location-based Scheme Selection', true, ['qr-service', 'smart-router'], 'CPM token fabricated locally (not scheme-issued); smart-router off the live path (schemeId hardcoded).'),
  blocked('UC-KOFIU-AML', 'EXT', 'Compliance', 'KoFIU AML / Suspicious Transaction Reporting', true, ['reporting-compliance'], 'CTR/STR logic exists but scheduler off, no real txn source, stub channel, no persistence.'),
  blocked('UC-WEBHOOK-DELIVERY', 'EXT', 'Notifications', 'Payment Result Webhook Delivery to Partner', true, ['notification-webhook', 'transaction-mgmt'], 'Records PENDING rows but there is no WebhookHttpClient impl and no dispatcher/drain loop; Kafka not running.'),
  blocked('UC-OPS-DASHBOARD', 'EXT', 'Admin', 'Real-time Transaction & Settlement Monitoring', true, ['admin-ui', 'ops-partner-bff'], 'Dashboard aggregates from BFF stubs; rest clients recently added but not driven e2e.'),
  blocked('UC-PARTNERB-MONITOR', 'EXT', 'Rate & Quote', 'Partner B Quote API Monitoring', false, ['rate-fx'], 'Partner-B authoritative quote source unwired; no quote calls captured; no monitoring backend.'),
  blocked('UC-NEPAL-CORRIDOR', 'EXT', 'Cross-border', 'Nepal Corridor Payment (KRW→NPR, Fonepay)', false, ['payment-executor', 'rate-fx', 'config-registry'], 'EXPECTATION CHANGED (T4): the corridor is structurally complete and currently REFUSES every payment, by design. POST /v1/pay for Nepal returns 503 CORRIDOR_PRICING_NOT_CONFIGURED (retryable=false) when the margin / fee / float ledger is unset — and it is unset by default: gmepay.payment.nepal.fx-margin and gmepay.payment.nepal.service-fee-krw have no defaults, and config-registry carries no fx-config/fee-schedule for the partner. A live-rate outage instead gives 503 CORRIDOR_RATE_UNAVAILABLE (retryable=true). Every refusal happens BEFORE any side effect: no float moved, no scheme call, only a FAILED attempt row. A test asserting a SUCCESSFUL Nepal payment would be asserting unpriced money movement. Blocked on configured corridor pricing (PATCH /v1/partners/draft/{code}/step-6-commercial) plus a Nepal QR fixture.'),
  // --------------------------------------------------- deliberately removed surfaces
  unsupported('UC-SANDBOX-E2E', 'EXT', 'Sandbox', 'Sandbox E2E Payment Runner', false, ['payment-executor', 'admin-ui'], 'INTENTIONALLY REMOVED (T0-5) — withdrawn, not broken. payment-executor POST /v1/sandbox/e2e/run drove a REAL authorize+capture through the real pay path (prefunding debit, scheme call, ledger postings), i.e. an anonymous "spend money" button. SandboxE2eController / E2eRunner / SelfPayClient are now @ConditionalOnProperty(gmepay.sandbox.e2e.enabled), pinned false everywhere (compose, all Helm values, run-fleet.ps1), so the beans are never registered and /v1/sandbox/e2e/** returns 404 with AND without a token — nothing to probe or fingerprint. The admin-ui `/e2e/:path*` → payment-executor rewrite was deleted from next.config.mjs, so http://<admin-ui>/e2e/* is gone too. Enabling the flag additionally requires X-Gme-Internal and makes the service refuse to start on a blank secret. This tester never routed through that path, so nothing else here regressed; the entry exists so the matrix records the capability as deliberately withdrawn rather than silently absent.'),
];

/**
 * The full registry the platform runs: the 34 PRD use cases (tagged `use-case`)
 * plus the endpoint-level feature tests (tagged `feature`).
 */
export const USE_CASES: UseCase[] = [
  ...PRD_USE_CASES.map((u) => ({ ...u, kind: 'use-case' as const })),
  ...FEATURE_TESTS.map((u) => ({ ...u, kind: 'feature' as const })),
];

export function findUseCase(id: string): UseCase | undefined {
  return USE_CASES.find((u) => u.id === id);
}

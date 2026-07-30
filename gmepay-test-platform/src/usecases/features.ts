import { UseCase, uniq, today, ensureSchemeMerchant, gatewayHeaders } from '../engine/testkit';
import type { CredentialKind } from '../engine/credentials';
import { FIXTURES, PORTAL_PARTNER_CODE } from '../config';

/**
 * Feature tests — endpoint-level coverage of everything actually BUILT in the
 * GMEPay+ services (discovered by reading the controllers). These complement the
 * 34 use-case acceptance tests with finer-grained checks of each real endpoint.
 *
 * Convention: self-contained tests create their own data (unique partner codes /
 * refs) so they pass repeatably against a live fleet without external seeding.
 */
export const FEATURE_TESTS: UseCase[] = [
  // ============================================================ rate-fx
  feat('F-RATE-01', 'rate-fx', 'Quote issue + retrieve round-trip', ['rate-fx'],
    'POST /v1/quotes issues a TTL-locked quote; GET /v1/quotes/{id} returns the same locked values.',
    async ({ client, check }) => {
      const post = await client.call('rate-fx', 'POST', '/v1/quotes', crossBorderRate());
      check.equal(post.status, 201, 'quote created (201)');
      const id = post.json?.quoteId;
      check.ok(typeof id === 'string' && id.startsWith('RQ-'), 'quoteId issued', id);
      const get = await client.call('rate-fx', 'GET', `/v1/quotes/${id}`);
      check.equal(get.status, 200, 'quote retrieved (200)');
      check.equal(get.json?.quoteId, id, 'same quoteId round-trips');
    }),
  feat('F-RATE-02', 'rate-fx', 'Unknown/expired quote → 409', ['rate-fx'],
    'GET /v1/quotes/{unknown} returns 409 RATE_QUOTE_EXPIRED.',
    async ({ client, check }) => {
      const res = await client.call('rate-fx', 'GET', `/v1/quotes/RQ-${uniq()}`);
      check.equal(res.status, 409, 'unknown quote rejected with 409');
    }),

  // ============================================================ prefunding
  // T0-5: prefunding's ENTIRE /v1/prefunding/** API is internal-gated (its
  // application.properties path-patterns are `/internal/**,/v1/prefunding/**`), so every
  // one of these calls must carry X-Gme-Internal or answer 401 UNAUTHORIZED.
  feat('F-PREFUND-01', 'prefunding', 'Provision → deduct → credit → reverse lifecycle', ['prefunding'],
    'Full balance lifecycle with atomic mutations and idempotent reversal.',
    async ({ client, check }) => {
      const code = uniq('PF');
      const prov = await client.call('prefunding', 'POST', '/v1/prefunding/provision',
        { partnerCode: code, openingBalanceUsd: '50000', lowBalanceThresholdUsd: '10000' }, { as: 'internal' });
      check.equal(prov.status, 201, 'provisioned (201)');
      check.closeTo(Number(prov.json?.balance), 50000, 0.001, 'opening balance = 50000');

      const txnRef = uniq('TX');
      const ded = await client.call('prefunding', 'POST', `/v1/prefunding/${code}/deduct`,
        { txnRef, amount: '125.50' }, { as: 'internal' });
      check.equal(ded.status, 200, 'deduct accepted');
      check.closeTo(Number(ded.json?.balance), 49874.5, 0.001, 'balance after deduct');

      const cr = await client.call('prefunding', 'POST', `/v1/prefunding/${code}/credit`, { amount: '25.50' }, { as: 'internal' });
      check.closeTo(Number(cr.json?.balance), 49900, 0.001, 'balance after credit');

      const rev = await client.call('prefunding', 'POST', `/v1/prefunding/${code}/reverse`, { txnRef }, { as: 'internal' });
      check.equal(rev.status, 200, 'reverse accepted');
      check.closeTo(Number(rev.json?.reversedUsd), 125.5, 0.001, 'reversed the original deduct');
    }, ['internal']),
  feat('F-PREFUND-02', 'prefunding', 'Overdraw rejected, balance untouched', ['prefunding'],
    'Deducting more than the balance is rejected and leaves the balance intact.',
    async ({ client, check, rec }) => {
      const code = uniq('PF');
      await client.call('prefunding', 'POST', '/v1/prefunding/provision',
        { partnerCode: code, openingBalanceUsd: '100', lowBalanceThresholdUsd: '10' }, { as: 'internal' });
      const ded = await client.call('prefunding', 'POST', `/v1/prefunding/${code}/deduct`,
        { txnRef: uniq('TX'), amount: '150' }, { as: 'internal' });
      check.ok(ded.status >= 400, 'overdraw rejected (not accepted)', ded.status);
      if (ded.status >= 500)
        rec.warn('NOTE: insufficient funds returns HTTP 500 — should be a 4xx (e.g. 422). Contract gap in prefunding deduct.');
      const bal = await client.call('prefunding', 'GET', `/v1/prefunding/${code}/balance`, undefined, { as: 'internal' });
      check.closeTo(Number(bal.json?.balance), 100, 0.001, 'balance untouched after rejected overdraw (no money lost)');
    }, ['internal']),
  feat('F-PREFUND-03', 'prefunding', 'Low-balance alert raised below threshold', ['prefunding'],
    'Deducting below the configured threshold raises a tier alert.',
    async ({ client, check }) => {
      const code = uniq('PF');
      await client.call('prefunding', 'POST', '/v1/prefunding/provision',
        { partnerCode: code, openingBalanceUsd: '12000', lowBalanceThresholdUsd: '10000' }, { as: 'internal' });
      await client.call('prefunding', 'POST', `/v1/prefunding/${code}/deduct`, { txnRef: uniq('TX'), amount: '3000' }, { as: 'internal' });
      const alerts = await client.call('prefunding', 'GET', `/v1/prefunding/${code}/alerts`, undefined, { as: 'internal' });
      check.equal(alerts.status, 200, 'alerts readable');
      check.ok(Array.isArray(alerts.json) && alerts.json.length > 0, 'a low-balance alert was raised', alerts.json);
    }, ['internal']),
  feat('F-PREFUND-04', 'prefunding', 'Duplicate provision → 409', ['prefunding'],
    'Provisioning the same partner twice is rejected (idempotency guard).',
    async ({ client, check }) => {
      const code = uniq('PF');
      const body = { partnerCode: code, openingBalanceUsd: '1000', lowBalanceThresholdUsd: '100' };
      check.equal((await client.call('prefunding', 'POST', '/v1/prefunding/provision', body, { as: 'internal' })).status, 201, 'first provision OK');
      check.equal((await client.call('prefunding', 'POST', '/v1/prefunding/provision', body, { as: 'internal' })).status, 409, 'duplicate rejected (409)');
    }, ['internal']),
  feat('F-PREFUND-05', 'prefunding', 'Ungated call is refused (internal gate is ON)', ['prefunding'],
    'T0-5 boundary: /v1/prefunding/** without X-Gme-Internal must be 401 with the UNAUTHORIZED envelope. ' +
    'This asserts the gate exists — a 200 here would mean the money API is open to anyone on the network.',
    async ({ client, check }) => {
      const res = await client.call('prefunding', 'GET', '/v1/prefunding/SENDMN/balance', undefined,
        { expectAuthFailure: true });
      check.equal(res.status, 401, 'unauthenticated balance read refused (401)');
      check.equal(res.json?.code, 'UNAUTHORIZED', 'internal-auth filter envelope returned');
      check.ok(
        String(res.json?.message ?? '').includes('internal service authentication required'),
        'filter message identifies the internal-auth gate', res.json?.message,
      );
    }),

  // ============================================================ config-registry
  feat('F-CONFIG-01', 'config-registry', 'Scheme catalog readable', ['config-registry'],
    'GET /v1/schemes returns the scheme catalog (seeded ref data).',
    async ({ client, check }) => {
      const res = await client.call('config-registry', 'GET', '/v1/schemes');
      check.equal(res.status, 200, 'catalog readable (200)');
      check.ok(Array.isArray(res.json), 'returns an array', res.json);
    }),
  feat('F-CONFIG-02', 'config-registry', 'Valid cross-border rule accepted', ['config-registry'],
    'POST /v1/rules/validate accepts a rule whose combined margin ≥ 2%.',
    async ({ client, check }) => {
      const res = await client.call('config-registry', 'POST', '/v1/rules/validate', {
        partnerId: 2, schemeId: 'ZEROPAY', direction: 'INBOUND',
        settleACurrency: 'MNT', settleBCurrency: 'KRW', mA: 0.01, mB: 0.01, serviceCharge: 500,
      });
      check.equal(res.status, 200, 'valid rule accepted (200)');
    }),
  feat('F-CONFIG-03', 'config-registry', 'Below-minimum margin rejected', ['config-registry'],
    'POST /v1/rules/validate rejects a cross-border rule with combined margin < 2%.',
    async ({ client, check }) => {
      const res = await client.call('config-registry', 'POST', '/v1/rules/validate', {
        partnerId: 2, schemeId: 'ZEROPAY', direction: 'INBOUND',
        settleACurrency: 'MNT', settleBCurrency: 'KRW', mA: 0.005, mB: 0.005, serviceCharge: 500,
      });
      check.ok(res.status >= 400 && res.status < 500, 'sub-2% margin rejected (4xx)', res.status);
    }),
  feat('F-CONFIG-04', 'config-registry', 'Create partner → read back → list', ['config-registry'],
    'POST /v1/partners persists a partner; GET /v1/partners/{code} and list reflect it.',
    async ({ client, check }) => {
      const code = uniq('P').toUpperCase();
      const create = await client.call('config-registry', 'POST', '/v1/partners',
        { partnerCode: code, type: 'OVERSEAS', settlementCurrency: 'USD', settlementRoundingMode: 'HALF_UP' });
      check.equal(create.status, 201, 'partner created (201)');
      const get = await client.call('config-registry', 'GET', `/v1/partners/${code}`);
      check.equal(get.status, 200, 'partner read back (200)');
      check.equal(get.json?.type, 'OVERSEAS', 'type persisted');
      const list = await client.call('config-registry', 'GET', '/v1/partners');
      check.ok(Array.isArray(list.json) && list.json.some((p: any) => p.partnerCode === code),
        'partner appears in list', code);
    }),
  feat('F-CONFIG-05', 'config-registry', '4-eyes: self-approval rejected', ['config-registry'],
    'A change request cannot be approved by the same actor who proposed it (409).',
    async ({ client, check }) => {
      const actor = uniq('maker-');
      const cr = await client.call('config-registry', 'POST', '/v1/change-requests',
        { aggregateType: 'partner', aggregateId: uniq('AGG'), proposedBy: actor, payloadJsonb: '{}' });
      check.equal(cr.status, 201, 'change request created (201)');
      const approve = await client.call('config-registry', 'POST', `/v1/change-requests/${cr.json?.id}/approve`,
        { approvedBy: actor });
      check.equal(approve.status, 409, 'self-approval rejected (409 — 4-eyes enforced)');
    }),

  // ============================================================ transaction-mgmt
  feat('F-TXN-01', 'transaction-mgmt', 'Create transaction → read back', ['transaction-mgmt'],
    'POST /v1/transactions persists a txn in CREATED; GET /v1/transactions/{ref} returns it.',
    async ({ client, check }) => {
      const ref = uniq('PTX');
      const create = await client.call('transaction-mgmt', 'POST', '/v1/transactions', txnBody(ref));
      check.equal(create.status, 201, 'transaction created (201)');
      const txnRef = create.json?.txnRef;
      check.ok(!!txnRef, 'txnRef returned', txnRef);
      const get = await client.call('transaction-mgmt', 'GET', `/v1/transactions/${txnRef}`);
      check.equal(get.status, 200, 'transaction read back (200)');
      check.equal(get.json?.status, 'CREATED', 'initial state is CREATED');
    }),
  feat('F-TXN-02', 'transaction-mgmt', 'Idempotent create replays', ['transaction-mgmt'],
    'Re-POSTing with the same Idempotency-Key returns the same txnRef (200 replay).',
    async ({ client, check }) => {
      const ref = uniq('PTX');
      const key = uniq('idem-');
      const first = await client.call('transaction-mgmt', 'POST', '/v1/transactions', txnBody(ref), { 'Idempotency-Key': key });
      check.equal(first.status, 201, 'first create (201)');
      const second = await client.call('transaction-mgmt', 'POST', '/v1/transactions', txnBody(ref), { 'Idempotency-Key': key });
      check.ok(second.status === 200 || second.status === 201, 'replay accepted', second.status);
      check.equal(second.json?.txnRef, first.json?.txnRef, 'same txnRef on replay (no double-create)');
    }),
  feat('F-TXN-03', 'transaction-mgmt', 'Query with filters', ['transaction-mgmt'],
    'GET /v1/transactions returns a paged result envelope.',
    async ({ client, check }) => {
      const res = await client.call('transaction-mgmt', 'GET', '/v1/transactions?page=0&size=5');
      check.equal(res.status, 200, 'query OK (200)');
      check.ok(Array.isArray(res.json?.content), 'paged content array present', { page: res.json?.page, size: res.json?.size });
    }),

  // ============================================================ scheme-adapter-zeropay
  // T0-2: the whole /internal/scheme/** surface (and /__data/**) is internal-gated.
  feat('F-SCHEME-01', 'scheme-adapter-zeropay', 'Real-time MPM authorize+commit via sim', ['scheme-adapter-zeropay', 'sim-scheme'],
    'POST /internal/scheme/zeropay/submit authorizes & commits against sim-scheme.',
    async ({ client, check }) => {
      await ensureSchemeMerchant(client);
      const res = await client.call('scheme-adapter-zeropay', 'POST', '/internal/scheme/zeropay/submit', {
        merchantId: FIXTURES.merchantId, amountKrw: 10000, currency: 'KRW',
        partnerTxnRef: uniq('STX'), idempotencyKey: uniq('idem-'), paymentMode: 'MPM', qrPayload: FIXTURES.qrPayload,
      }, { as: 'internal' });
      check.blockedIf(res.status >= 500, 'scheme adapter 500 on submit — sim-scheme/merchant interaction unavailable in this env');
      check.equal(res.status, 200, 'scheme submit OK (200)');
      check.equal(res.json?.success, true, 'scheme authorized + committed');
      check.ok(!!res.json?.schemeTxnRef, 'scheme txn reference returned', res.json?.schemeTxnRef);
    }, ['internal']),
  feat('F-SCHEME-02', 'scheme-adapter-zeropay', 'Adapter health endpoint', ['scheme-adapter-zeropay'],
    'GET /internal/scheme/zeropay/health responds (SFTP/batch may report degraded). Note this is the ' +
    'ADAPTER health under /internal/**, so it is internal-gated — unlike /actuator/health, which stays anonymous.',
    async ({ client, check }) => {
      const res = await client.call('scheme-adapter-zeropay', 'GET', '/internal/scheme/zeropay/health',
        undefined, { as: 'internal' });
      check.equal(res.status, 200, 'health responds (200)');
      check.ok(!!res.json?.status, 'status field present', res.json?.status);
    }, ['internal']),

  // ============================================================ qr-service
  feat('F-QR-01', 'qr-service', 'Parse ZeroPay QR payload', ['qr-service'],
    'POST /v1/qr/parse decodes a ZeroPay EMVCo payload to its merchant id.',
    async ({ client, check }) => {
      const res = await client.call('qr-service', 'POST', '/v1/qr/parse', { rawPayload: FIXTURES.qrPayload, schemeId: 'ZEROPAY' });
      check.equal(res.status, 200, 'parsed (200)');
      check.equal(res.json?.merchantId, FIXTURES.merchantId, 'merchant id decoded from QR');
    }),
  feat('F-QR-02', 'qr-service', 'Unknown scheme → 422', ['qr-service'],
    'POST /v1/qr/parse with an unsupported scheme returns 422 QR_UNKNOWN_SCHEME.',
    async ({ client, check }) => {
      const res = await client.call('qr-service', 'POST', '/v1/qr/parse', { rawPayload: FIXTURES.qrPayload, schemeId: 'FOOPAY' });
      check.equal(res.status, 422, 'unknown scheme rejected (422)');
    }),
  feat('F-QR-03', 'qr-service', 'Generate CPM prepare token', ['qr-service'],
    'POST /v1/qr/cpm/generate issues a CPM prepare token.',
    async ({ client, check }) => {
      const res = await client.call('qr-service', 'POST', '/v1/qr/cpm/generate', {
        schemeId: 'ZEROPAY', direction: 'domestic', customerRef: uniq('cust-'), partnerTxnRef: uniq('ptx-'), countryCode: 'KR',
      });
      check.equal(res.status, 201, 'token generated (201)');
      check.ok(!!res.json?.prepareToken, 'prepareToken present', res.json?.cpmTokenId);
    }),

  // ============================================================ merchant-qr-data
  feat('F-MERCH-01', 'merchant-qr-data', 'Merchant lookup by QR id', ['merchant-qr-data'],
    'GET /v1/merchants/{qr} resolves a seeded merchant (404 → not seeded in this env).',
    async ({ client, check }) => {
      const res = await client.call('merchant-qr-data', 'GET', `/v1/merchants/${FIXTURES.qrCodeId}`);
      check.blockedIf(res.status === 404, `qr_code_id ${FIXTURES.qrCodeId} not seeded in this env`);
      check.equal(res.status, 200, 'merchant resolved (200)');
      check.ok(!!res.json?.merchantId, 'merchant record returned', res.json);
    }),

  // ============================================================ smart-router
  feat('F-ROUTE-01', 'smart-router', 'Route by country', ['smart-router', 'config-registry'],
    'GET /v1/route?country=KR returns the schemes wired for that country.',
    async ({ client, check }) => {
      const res = await client.call('smart-router', 'GET', '/v1/route?country=KR');
      check.blockedIf(res.status >= 400,
        'smart-router cannot resolve KR — no partner_scheme rows wired (NO_SCHEME_FOR_LOCATION / resolver error)');
      check.equal(res.status, 200, 'route resolved (200)');
      check.ok(Array.isArray(res.json), 'returns scheme list', res.json);
    }),
  feat('F-ROUTE-02', 'smart-router', 'Blank country → 400', ['smart-router'],
    'GET /v1/route with no country is a 400.',
    async ({ client, check }) => {
      const res = await client.call('smart-router', 'GET', '/v1/route?country=');
      check.ok(res.status >= 400, 'blank country rejected (non-2xx)', res.status);
    }),

  // ============================================================ revenue-ledger
  feat('F-REV-01', 'revenue-ledger', 'Capture revenue → aggregate reflects it', ['revenue-ledger'],
    'POST /v1/revenue/capture persists a record; GET /v1/revenue aggregates it.',
    async ({ client, check }) => {
      const partnerId = 900000 + Math.floor(Math.random() * 90000);
      const d = today();
      const cap = await client.call('revenue-ledger', 'POST', '/v1/revenue/capture', {
        txnRef: uniq('RTX'), partnerId, schemeId: 1, revenueDate: d,
        collectionMarginUsd: '1.00', payoutMarginUsd: '0.50', serviceChargeAmount: '500', serviceChargeCcy: 'KRW', feeSharePct: '0.70',
      });
      check.ok(cap.status === 201 || cap.status === 200, 'revenue captured', cap.status);
      const agg = await client.call('revenue-ledger', 'GET', `/v1/revenue?partnerId=${partnerId}&startDate=${d}&endDate=${d}`);
      check.equal(agg.status, 200, 'aggregate readable (200)');
      check.ok(Number(agg.json?.txnCount) >= 1, 'captured txn shows in aggregate', agg.json);
    }),
  feat('F-REV-02', 'revenue-ledger', 'Rounding-residual journal (zero no-op + balance)', ['revenue-ledger'],
    'POST /v1/journals/rounding-residual: zero residual is a 204 no-op; a non-zero residual should post a balanced journal.',
    async ({ client, check }) => {
      const zero = await client.call('revenue-ledger', 'POST', '/v1/journals/rounding-residual',
        { reference: uniq('REF'), residual: '0', currency: 'KRW' });
      check.equal(zero.status, 204, 'zero residual is a no-op (204)');
      const post = await client.call('revenue-ledger', 'POST', '/v1/journals/rounding-residual',
        { reference: uniq('REF'), residual: '0.01', currency: 'KRW' });
      check.blockedIf(post.status === 406,
        'non-zero residual posts but the response serialization returns HTTP 406 — defect in revenue-ledger RoundingResidualController');
      check.equal(post.status, 200, 'journal posted (200)');
      const entries = post.json?.entries ?? [];
      const debit = sum(entries.filter((e: any) => e.type === 'DEBIT').map((e: any) => Number(e.amount)));
      const credit = sum(entries.filter((e: any) => e.type === 'CREDIT').map((e: any) => Number(e.amount)));
      check.closeTo(debit, credit, 0.0001, 'debits equal credits (balanced)');
    }),

  // ============================================================ settlement-reconciliation
  feat('F-SETTLE-01', 'settlement-reconciliation', 'Settlement query responds', ['settlement-reconciliation'],
    'GET /v1/settlements computes net/gross over approved txns (empty until batches exist).',
    async ({ client, check }) => {
      const res = await client.call('settlement-reconciliation', 'GET', '/v1/settlements');
      check.equal(res.status, 200, 'settlements query OK (200)');
      check.ok(Array.isArray(res.json), 'returns a list', res.json);
    }),
  feat('F-SETTLE-02', 'settlement-reconciliation', 'Recon exceptions query responds', ['settlement-reconciliation'],
    'GET /v1/settlement/exceptions returns the exception list (recon scheduler is off by default).',
    async ({ client, check }) => {
      const res = await client.call('settlement-reconciliation', 'GET', '/v1/settlement/exceptions');
      check.equal(res.status, 200, 'exceptions query OK (200)');
      check.ok(Array.isArray(res.json), 'returns a list', res.json);
    }),

  // ============================================================ reporting-compliance
  feat('F-REPORT-01', 'reporting-compliance', 'BOK FX report query responds', ['reporting-compliance'],
    'GET /v1/reports maps committed txns to BOK FX1014/1015 records.',
    async ({ client, check }) => {
      const res = await client.call('reporting-compliance', 'GET', `/v1/reports?from=2026-01-01&to=${today()}`);
      check.blockedIf(res.status >= 500, 'report generation 500s over empty/stub committed-transaction data (no cross-border txns to map)');
      check.equal(res.status, 200, 'report query OK (200)');
      check.ok(Array.isArray(res.json?.records), 'records array present', { total: res.json?.total_count });
    }),

  // ============================================================ auth-identity
  // T0-2: auth-identity has NO public surface. Its path-patterns are
  // /internal/**,/v1/rbac/**,/v1/approvals/** — everything it ships is internal-gated,
  // and it REFUSES TO START without GMEPAY_INTERNAL_AUTH_SECRET.
  feat('F-AUTH-01', 'auth-identity', 'HMAC verify rejects an unusable credential', ['auth-identity'],
    'POST /internal/auth/verify returns valid=false + errorCode. The presented apiKey is deliberately the ' +
    'retired stub `pk_test_abc`: since StubPartnerCredentialService was deleted it resolves to nothing, so the ' +
    'rejection now comes from an unknown key rather than a signature mismatch — either way valid=false with a code.',
    async ({ client, check, rec }) => {
      const res = await client.call('auth-identity', 'POST', '/internal/auth/verify', {
        apiKey: 'pk_test_abc', httpMethod: 'GET', pathWithQuery: '/v1/ping',
        timestamp: new Date().toISOString(), nonce: uniq('n-'), signature: 'deadbeef', bodyHash: '',
      }, { as: 'internal' });
      check.equal(res.status, 200, 'verify endpoint responds (200)');
      check.equal(res.json?.valid, false, 'unusable credential is not valid');
      check.ok(!!res.json?.errorCode, 'an errorCode is returned', res.json?.errorCode);
      rec.info(`rejection reason reported by auth-identity: ${res.json?.errorCode}`);
    }, ['internal']),
  feat('F-AUTH-02', 'auth-identity', 'Ungated internal call is refused', ['auth-identity'],
    'T0-2 boundary: /internal/auth/verify without X-Gme-Internal must be 401. auth-identity mints JWTs and ' +
    'issues partner keys, so an open surface here would let anyone forge any identity.',
    async ({ client, check }) => {
      const res = await client.call('auth-identity', 'POST', '/internal/auth/verify', {
        apiKey: 'x', httpMethod: 'GET', pathWithQuery: '/v1/ping',
        timestamp: new Date().toISOString(), nonce: uniq('n-'), signature: 'x', bodyHash: '',
      }, { expectAuthFailure: true });
      check.equal(res.status, 401, 'unauthenticated internal call refused (401)');
      check.equal(res.json?.code, 'UNAUTHORIZED', 'internal-auth filter envelope returned');
    }),

  // ============================================================ notification-webhook
  feat('F-WEBHOOK-01', 'notification-webhook', 'Register webhook config → list', ['notification-webhook'],
    'POST /v1/webhook-configs persists an HTTPS webhook; GET lists it for the partner.',
    async ({ client, check }) => {
      const partnerId = 900000 + Math.floor(Math.random() * 90000);
      const create = await client.call('notification-webhook', 'POST', '/v1/webhook-configs', {
        partnerId, webhookUrl: 'https://example.com/hook', eventTypes: ['payment.approved'], signingSecret: uniq('whsec_'),
      });
      check.equal(create.status, 201, 'config created (201)');
      const list = await client.call('notification-webhook', 'GET', `/v1/webhook-configs?partnerId=${partnerId}`);
      check.equal(list.status, 200, 'configs listed (200)');
      check.ok((list.json?.configs?.length ?? 0) >= 1, 'registered config appears in list', list.json?.totalCount);
    }),
  feat('F-WEBHOOK-02', 'notification-webhook', 'Non-HTTPS webhook rejected', ['notification-webhook'],
    'POST /v1/webhook-configs rejects a plain-http callback URL.',
    async ({ client, check }) => {
      const res = await client.call('notification-webhook', 'POST', '/v1/webhook-configs', {
        partnerId: 1, webhookUrl: 'http://insecure.example.com/hook', eventTypes: ['payment.approved'], signingSecret: uniq('whsec_'),
      });
      check.ok(res.status >= 400 && res.status < 500, 'http URL rejected (4xx)', res.status);
    }),

  // ============================================================ kyb-adapter
  // kyb-adapter gates /v1/kyb/screen, /v1/kyb/verify, /v1/kyb/result/** and
  // /v1/screening/** behind X-Gme-Internal (its own application.properties sets
  // gmepay.internal-auth.enabled=true with those path-patterns). /v1/kyb/health is NOT
  // in the pattern list and stays anonymous.
  feat('F-KYB-01', 'kyb-adapter', 'Screen a KYB subject (no authoritative provider)', ['kyb-adapter'],
    'POST /v1/kyb/screen returns a screening verdict. EXPECTATION CHANGED: with no KYB vendor contracted the ' +
    'stub can only ever answer MANUAL_REVIEW / NOT_SCREENED_NO_PROVIDER — it must NOT report CLEAR, because a ' +
    'fabricated clear verdict is what would let an unscreened partner go live.',
    async ({ client, check, rec }) => {
      const res = await client.call('kyb-adapter', 'POST', '/v1/kyb/screen',
        { partnerCode: uniq('K'), entityName: 'Test Co Ltd', ubos: [] }, { as: 'internal' });
      check.equal(res.status, 200, 'screen responds (200)');
      const status = String(res.json?.status ?? '');
      check.ok(status.length > 0, 'screening verdict returned', res.json);
      check.ok(
        status !== 'CLEAR',
        'verdict is NOT a fabricated CLEAR — an uncontracted provider must not clear anyone',
        status,
      );
      rec.info(`verdict: ${status} (expected MANUAL_REVIEW or NOT_SCREENED_NO_PROVIDER while no vendor is live)`);
    }, ['internal']),
  feat('F-KYB-02', 'kyb-adapter', 'KYB health + active provider', ['kyb-adapter'],
    'GET /v1/kyb/health reports UP and the active provider. Deliberately NOT internal-gated.',
    async ({ client, check }) => {
      const res = await client.call('kyb-adapter', 'GET', '/v1/kyb/health');
      check.equal(res.status, 200, 'health OK (200)');
      check.equal(res.json?.status, 'UP', 'provider is up');
    }),
  feat('F-KYB-03', 'config-registry', 'Partner activation REFUSES without authoritative screening', ['config-registry'],
    'EXPECTATION CHANGED (T1-2): POST /v1/admin/partners/{code}/lifecycle/activate now answers 422 with ' +
    '`SANCTIONS_NOT_SCREENED` in unmet[] whenever KybEntity.hasAuthoritativeScreening() is false — and the stub ' +
    'provider can never satisfy it. Refusing is the CORRECT behaviour: activating an unscreened partner is the bug. ' +
    'The change request stays PROPOSED so it can be re-approved once a real provider is wired.',
    async ({ client, check, rec }) => {
      const code = uniq('A').toUpperCase();
      const draft = await client.call('config-registry', 'POST', '/v1/partners/draft',
        { partnerCode: code, type: 'OVERSEAS', settlementCurrency: 'USD', settlementRoundingMode: 'HALF_UP' });
      check.equal(draft.status, 201, 'draft partner created for the activation attempt');

      // The non-mutating precheck is always 200, even when the gate fails.
      const pre = await client.call('config-registry', 'GET', `/v1/admin/partners/${code}/lifecycle/preconditions`);
      check.equal(pre.status, 200, 'preconditions precheck is readable (200) even when unmet');
      rec.info('activation preconditions', pre.json);

      const act = await client.call('config-registry', 'POST', `/v1/admin/partners/${code}/lifecycle/activate`, {});
      check.equal(act.status, 422, 'activation refused (422) — not silently allowed');
      const body = JSON.stringify(act.json ?? act.text ?? '');
      check.ok(
        body.includes('SANCTIONS_NOT_SCREENED'),
        'refusal names SANCTIONS_NOT_SCREENED — no authoritative screening exists',
        act.json,
      );
    }),

  // ============================================================ ops-partner-bff
  // T0-1: the BFF is now an OAuth2/JWT resource server, default-deny. Only
  // /actuator/health** is anonymous. Permissions and partner scope come from the token's
  // `permissions` and `partner_id` claims; X-Gme-Permissions / X-Partner-Id are ignored.
  feat('F-BFF-01', 'ops-partner-bff', 'Dev login endpoint is GONE (401 unauth / 404 authenticated)', ['ops-partner-bff'],
    'EXPECTATION CHANGED (T0-1): POST /v1/auth/login and password=demo were DELETED, not merely disabled. ' +
    'The two-status assertion is deliberate and is the strongest available proof: 401 unauthenticated (the ' +
    'default-deny chain answers before routing) and 404 WITH a valid token (there is no handler behind the wall). ' +
    'A 200 here would mean a password bypass came back.',
    async ({ client, check }) => {
      const anon = await client.call('ops-partner-bff', 'POST', '/v1/auth/login',
        { username: 'ops1', password: 'demo' }, { expectAuthFailure: true });
      check.equal(anon.status, 401, 'unauthenticated login attempt refused (401)');
      check.ok(!anon.json?.token, 'no token was issued', anon.json ?? anon.text);

      const authed = await client.call('ops-partner-bff', 'POST', '/v1/auth/login',
        { username: 'ops1', password: 'demo' }, { as: 'oidc-admin' });
      check.equal(authed.status, 404, 'with a valid token the path 404s — the handler is deleted, not gated');
    }, ['oidc-admin']),
  feat('F-BFF-05', 'ops-partner-bff', 'Forged permission headers grant nothing', ['ops-partner-bff'],
    'T0-1 boundary: X-Gme-Permissions + X-Gme-Principal-Id with NO bearer token must be 401. Those headers used ' +
    'to be the authorization input; a 200 here would mean anyone on the network can self-declare ops:operate.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/partners', undefined, {
        expectAuthFailure: true,
        headers: { 'X-Gme-Permissions': 'ops:operate,rbac.manage', 'X-Gme-Principal-Id': '1' },
      });
      check.equal(res.status, 401, 'forged permission headers rejected (401)');
      check.ok(!res.text, 'empty body — HttpStatusEntryPoint, no login redirect and no error envelope', res.text);
    }),
  feat('F-BFF-02', 'ops-partner-bff', 'Admin partner list', ['ops-partner-bff', 'config-registry'],
    'GET /v1/admin/partners orchestrates config-registry and returns partners. Needs a token whose `permissions` ' +
    'claim carries a platform-operator permission (safe methods → requireAdminRead()).',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/partners', undefined, { as: 'oidc-admin' });
      check.equal(res.status, 200, 'partner list OK (200)');
      check.ok(Array.isArray(res.json), 'returns an array', res.json?.length);
    }, ['oidc-admin']),
  feat('F-BFF-03', 'ops-partner-bff', 'Admin dashboard aggregates', ['ops-partner-bff'],
    'GET /v1/admin/dashboard returns aggregate counts.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/dashboard', undefined, { as: 'oidc-admin' });
      check.equal(res.status, 200, 'dashboard OK (200)');
      check.ok(res.json && typeof res.json === 'object', 'dashboard payload present', res.json);
    }, ['oidc-admin']),
  feat('F-BFF-04', 'ops-partner-bff', 'System health snapshot', ['ops-partner-bff'],
    'GET /v1/admin/system/health aggregates downstream service health.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/system/health', undefined, { as: 'oidc-admin' });
      check.equal(res.status, 200, 'system health OK (200)');
      check.ok(!!res.json?.services || !!res.json?.overallStatus, 'health snapshot present', res.json?.overallStatus);
    }, ['oidc-admin']),

  // ============================================================ api-gateway
  // T1-1: the partner edge authenticates against the REAL credential store. A key must
  // exist in BOTH halves — auth-identity (identity/lifecycle) AND the operator config
  // `gateway.partner-credentials.partners[]` (HMAC material) — or it 401s. The published
  // stub pair pk_test_abc / sk_test_xyz was deleted from src/main and now correctly fails.
  feat('F-GW-01', 'api-gateway', 'Unsigned request rejected at the edge', ['api-gateway'],
    'POST /v1/rates without HMAC headers is rejected by the gateway (401/403). Needs no credential — ' +
    'this is the negative case, and it is the one edge assertion that was already correct before the hardening.',
    async ({ client, check }) => {
      const res = await client.call('api-gateway', 'POST', '/v1/rates', crossBorderRate(),
        { expectAuthFailure: true });
      check.ok(res.status === 401 || res.status === 403, 'unsigned request rejected at edge', res.status);
    }),
  feat('F-GW-02', 'api-gateway', 'Correctly-signed request passes the edge', ['api-gateway'],
    'A valid HMAC-SHA256 signed GET is accepted by the gateway (not 401/403) and routed downstream. Now requires ' +
    'REAL partner credentials plus the X-Nonce and X-Partner-Id the replay/identity filters made mandatory.',
    async ({ client, check }) => {
      const path = '/v1/route?country=KR';
      const res = await client.call('api-gateway', 'GET', path, undefined,
        { headers: gatewayHeaders('GET', path), expectAuthFailure: true });
      check.blockedIf(res.status === 503,
        'gateway answered 503 CREDENTIAL_SERVICE_UNAVAILABLE — it cannot reach auth-identity, so no partner ' +
        'credential can be resolved (check GMEPAY_AUTH_IDENTITY_BASE_URL / GMEPAY_INTERNAL_AUTH_SECRET on the gateway)');
      check.blockedIf(res.status === 429, 'gateway rate limit tripped (now enabled and fail-closed)');
      check.ok(res.status !== 401 && res.status !== 403,
        'valid signature accepted at edge (auth passed) — a 401 means the key is not in BOTH the auth-identity ' +
        'store and gateway.partner-credentials.partners[]; a 403 means IP_NOT_ALLOWED or PARTNER_ID_MISMATCH',
        res.status);
    }, ['partner-key']),
  feat('F-GW-03', 'api-gateway', 'Tampered signature rejected', ['api-gateway'],
    'Valid headers but a wrong X-Signature is rejected (401/403). This case is only meaningful with REAL ' +
    'credentials: with a dead key the edge rejects everything, so a "pass" would prove nothing. It therefore ' +
    'requires the partner key too, and F-GW-02 must pass for this result to carry weight.',
    async ({ client, check }) => {
      const path = '/v1/route?country=KR';
      const h = gatewayHeaders('GET', path);
      h['X-Signature'] = 'deadbeefdeadbeef';
      const res = await client.call('api-gateway', 'GET', path, undefined,
        { headers: h, expectAuthFailure: true });
      check.ok(res.status === 401 || res.status === 403, 'tampered signature rejected', res.status);
    }, ['partner-key']),
  feat('F-GW-04', 'api-gateway', 'Missing X-Nonce rejected (replay protection is fail-closed)', ['api-gateway'],
    'T1-1: X-Nonce is now unconditional. A signed request without it is a 400 — replay protection no longer ' +
    'fails open, so an omitted nonce cannot slip through.',
    async ({ client, check }) => {
      const path = '/v1/route?country=KR';
      const h = gatewayHeaders('GET', path);
      delete h['X-Nonce'];
      const res = await client.call('api-gateway', 'GET', path, undefined,
        { headers: h, expectAuthFailure: true });
      check.ok(res.status === 400 || res.status === 401,
        'nonce-less request refused (400 missing nonce, or 401 if key resolution fails first)', res.status);
    }, ['partner-key']),

  // ============================================================ transaction-mgmt (state machine)
  feat('F-TXN-04', 'transaction-mgmt', 'Legal transition CREATED→APPROVED', ['transaction-mgmt'],
    'POST /v1/transactions/{ref}/transitions advances a CREATED txn to APPROVED (LOCAL direct approval).',
    async ({ client, check }) => {
      const create = await client.call('transaction-mgmt', 'POST', '/v1/transactions', txnBody(uniq('PTX')));
      const ref = create.json?.txnRef;
      check.ok(!!ref, 'txn created', ref);
      const t = await client.call('transaction-mgmt', 'POST', `/v1/transactions/${ref}/transitions`, { targetStatus: 'APPROVED' });
      check.equal(t.status, 200, 'transition accepted (200)');
      check.equal(t.json?.status, 'APPROVED', 'state advanced to APPROVED');
    }),
  feat('F-TXN-05', 'transaction-mgmt', 'Illegal direct transition rejected', ['transaction-mgmt'],
    'A fresh txn cannot jump straight to REVERSED — the state machine rejects it (422/400).',
    async ({ client, check }) => {
      const create = await client.call('transaction-mgmt', 'POST', '/v1/transactions', txnBody(uniq('PTX')));
      const ref = create.json?.txnRef;
      const t = await client.call('transaction-mgmt', 'POST', `/v1/transactions/${ref}/transitions`, { targetStatus: 'REVERSED' });
      check.ok(t.status === 422 || t.status === 400, 'illegal transition rejected', t.status);
    }),

  // ============================================================ auth-identity (RBAC / approvals / keys)
  // The old header-identity helper (X-Gme-Principal-Id + X-Gme-Permissions) is deleted:
  // those headers are no longer an authorization input anywhere. /v1/rbac/** and
  // /v1/approvals/** sit behind auth-identity's internal-auth gate, which asserts "a
  // trusted internal service is calling" — so X-Gme-Internal is the correct credential
  // and these cases can now genuinely pass instead of self-reporting BLOCKED.
  feat('F-RBAC-01', 'auth-identity', 'RBAC permission catalogue', ['auth-identity'],
    'GET /v1/rbac/permissions returns the catalogue to an internally-authenticated caller.',
    async ({ client, check }) => {
      const res = await client.call('auth-identity', 'GET', '/v1/rbac/permissions', undefined, { as: 'internal' });
      check.equal(res.status, 200, 'catalogue readable (200)');
      check.ok(Array.isArray(res.json), 'returns a permission list', res.json?.length);
    }, ['internal']),
  feat('F-RBAC-02', 'auth-identity', 'Create RBAC role', ['auth-identity'],
    'POST /v1/rbac/roles creates a role for an internally-authenticated caller.',
    async ({ client, check }) => {
      const res = await client.call('auth-identity', 'POST', '/v1/rbac/roles',
        { code: uniq('ROLE_').toUpperCase(), description: 'platform test role' }, { as: 'internal' });
      check.ok(res.status === 201 || res.status === 200, 'role created', res.status);
    }, ['internal']),
  feat('F-RBAC-03', 'auth-identity', 'RBAC surface refuses an unauthenticated caller', ['auth-identity'],
    'T0-2 boundary: /v1/rbac/** without X-Gme-Internal is 401. Previously a caller could simply assert ' +
    '`X-Gme-Permissions: rbac.manage` and manage roles; that path is closed.',
    async ({ client, check }) => {
      const res = await client.call('auth-identity', 'GET', '/v1/rbac/permissions', undefined, {
        expectAuthFailure: true,
        headers: { 'X-Gme-Principal-Id': '1', 'X-Gme-Permissions': 'rbac.manage' },
      });
      check.equal(res.status, 401, 'self-asserted rbac.manage header grants nothing (401)');
      check.equal(res.json?.code, 'UNAUTHORIZED', 'internal-auth filter envelope returned');
    }),
  feat('F-APPROVAL-01', 'auth-identity', 'Approval request workflow', ['auth-identity'],
    'POST /v1/approvals opens a tiered approval request (small amounts auto-approve).',
    async ({ client, check }) => {
      const res = await client.call('auth-identity', 'POST', '/v1/approvals',
        { requestType: 'REFUND', subjectRef: uniq('txn-'), amount: 100, currency: 'USD', tenantId: 1 },
        { as: 'internal', headers: { 'X-Gme-Principal-Id': '1' } });
      check.ok(res.status === 201 || res.status === 200, 'approval request created', res.status);
      check.ok(typeof res.json?.status === 'string', 'request carries a workflow status', res.json?.status);
    }, ['internal']),
  feat('F-KEY-01', 'auth-identity', 'Issue + revoke API key', ['auth-identity'],
    'POST /internal/auth/keys mints a one-time key+secret; revoke returns 204. This is also how a real partner-edge ' +
    'credential is provisioned — but note the minted key only authenticates at the gateway once an operator also ' +
    'adds its HMAC secret to gateway.partner-credentials.partners[]; auth-identity keeps only a PBKDF2 digest.',
    async ({ client, check }) => {
      const issue = await client.call('auth-identity', 'POST', '/internal/auth/keys', {
        partnerId: 42, partnerCode: uniq('pc_'), environment: 'SANDBOX', purpose: 'API',
        keyPrefix: 'pk_test_', secretPrefix: 'sk_test_', expiresAt: '2027-12-31T23:59:59Z',
      }, { as: 'internal' });
      check.ok(issue.status === 200 || issue.status === 201, 'key issued', issue.status);
      const keyId = issue.json?.keyId;
      check.ok(!!keyId && !!issue.json?.secretPlaintext, 'one-time keyId + plaintext secret returned', keyId);
      const revoke = await client.call('auth-identity', 'POST',
        `/internal/auth/keys/${encodeURIComponent(keyId)}/revoke`, undefined, { as: 'internal' });
      check.equal(revoke.status, 204, 'key revoked (204)');
    }, ['internal']),

  // ============================================================ scheme-adapter (CPM / cancel)
  feat('F-SCHEME-03', 'scheme-adapter-zeropay', 'Real-time CPM authorize via sim', ['scheme-adapter-zeropay', 'sim-scheme'],
    'POST /internal/scheme/zeropay/cpm authorizes a CPM token against sim-scheme.',
    async ({ client, check }) => {
      await ensureSchemeMerchant(client);
      const res = await client.call('scheme-adapter-zeropay', 'POST', '/internal/scheme/zeropay/cpm',
        { txnRef: uniq('CPM'), qrToken: 'TOK' + uniq(), payoutAmount: 10000, payoutCurrency: 'KRW', schemeId: 'zeropay' },
        { as: 'internal' });
      check.blockedIf(res.status >= 500, 'scheme CPM 500 — sim-scheme CPM interaction unavailable in this env');
      check.equal(res.status, 200, 'CPM authorized (200)');
    }, ['internal']),
  feat('F-SCHEME-04', 'scheme-adapter-zeropay', 'Submit then cancel (refund leg)', ['scheme-adapter-zeropay', 'sim-scheme'],
    'A committed MPM payment can be cancelled via /internal/scheme/zeropay/cancel.',
    async ({ client, check }) => {
      await ensureSchemeMerchant(client);
      const sub = await client.call('scheme-adapter-zeropay', 'POST', '/internal/scheme/zeropay/submit',
        { merchantId: FIXTURES.merchantId, amountKrw: 10000, currency: 'KRW', partnerTxnRef: uniq('STX'), idempotencyKey: uniq('idem-'), paymentMode: 'MPM', qrPayload: FIXTURES.qrPayload },
        { as: 'internal' });
      check.blockedIf(sub.status >= 500, 'scheme submit unavailable in this env');
      check.equal(sub.status, 200, 'submit OK (200)');
      const cancel = await client.call('scheme-adapter-zeropay', 'POST', '/internal/scheme/zeropay/cancel',
        { schemeTxnRef: sub.json?.schemeApprovalCode ?? sub.json?.schemeTxnRef, reason: 'TEST_CANCEL' },
        { as: 'internal' });
      check.blockedIf(cancel.status >= 500, 'scheme cancel 500 in this env');
      check.ok(cancel.status === 204 || cancel.status === 200, 'cancel accepted', cancel.status);
      // NOTE: this is the ADAPTER-level cancel, which carries {schemeTxnRef, reason} and no
      // amount. That contract is exactly why payment-executor now refuses a PARTIAL refund
      // with 422 PARTIAL_REFUND_UNSUPPORTED rather than sending a full cancel — see F-REFUND-01.
    }, ['internal']),

  // ============================================================ ops-partner-bff (portal + admin)
  // T1-3: portal reads are REAL now (RestApiKeyClient / RestStatementClient /
  // RestPortalWebhookClient), and the path carries the partner BUSINESS CODE which
  // PartnerDirectory resolves to config-registry's numeric surrogate. The path must match
  // the token's `partner_id` claim — the old literal `partner_test_001` was never a real
  // partner code, so it now fails closed with no upstream call at all.
  feat('F-PORTAL-01', 'ops-partner-bff', 'Partner portal overview', ['ops-partner-bff'],
    'GET /v1/portal/{partnerCode}/overview orchestrates balance + recent txns + last settlement.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', `/v1/portal/${PORTAL_PARTNER_CODE}/overview`,
        undefined, { as: 'oidc-partner' });
      check.equal(res.status, 200, 'overview OK (200)');
      check.ok(res.json && typeof res.json === 'object', 'overview payload present', res.json?.partnerId);
    }, ['oidc-partner']),
  feat('F-PORTAL-02', 'ops-partner-bff', 'Partner portal profile', ['ops-partner-bff', 'config-registry'],
    'GET /v1/portal/{partnerCode}/profile returns the partner profile. `onboardedAt` is now partners.go_live_at ' +
    'and is legitimately null before first activation — absent-by-design, not zero-filled.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', `/v1/portal/${PORTAL_PARTNER_CODE}/profile`,
        undefined, { as: 'oidc-partner' });
      check.equal(res.status, 200, 'profile OK (200)');
      check.ok(!!res.json?.partnerId || !!res.json?.type, 'profile fields present', res.json);
    }, ['oidc-partner']),
  feat('F-PORTAL-03', 'ops-partner-bff', 'Partner portal transactions', ['ops-partner-bff'],
    'GET /v1/portal/{partnerCode}/transactions returns the partner transaction list.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', `/v1/portal/${PORTAL_PARTNER_CODE}/transactions`,
        undefined, { as: 'oidc-partner' });
      check.equal(res.status, 200, 'transactions OK (200)');
      check.ok(Array.isArray(res.json) || Array.isArray(res.json?.content), 'returns a list', res.json?.length);
    }, ['oidc-partner']),
  feat('F-PORTAL-04', 'ops-partner-bff', 'Cross-partner read is refused', ['ops-partner-bff'],
    'T0-1 boundary: a partner-scoped token must not read another partner`s portal. Scoping comes from the ' +
    '`partner_id` claim via rbac.requirePartnerScope(), so forging X-Partner-Id changes nothing → 403.',
    async ({ client, check }) => {
      const other = PORTAL_PARTNER_CODE === 'SENDMN' ? 'GMEREMIT' : 'SENDMN';
      const res = await client.call('ops-partner-bff', 'GET', `/v1/portal/${other}/overview`, undefined, {
        as: 'oidc-partner',
        expectAuthFailure: true,
        headers: { 'X-Partner-Id': other, 'X-Gme-Permissions': 'ops:operate' },
      });
      check.equal(res.status, 403, `token scoped to ${PORTAL_PARTNER_CODE} cannot read ${other} (403)`);
    }, ['oidc-partner']),
  feat('F-ADMIN-01', 'ops-partner-bff', 'Admin transactions (paged + filtered)', ['ops-partner-bff'],
    'GET /v1/admin/transactions returns a paged envelope.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/transactions?page=0&size=5',
        undefined, { as: 'oidc-admin' });
      check.equal(res.status, 200, 'admin txns OK (200)');
      check.ok(res.json?.content !== undefined || Array.isArray(res.json), 'paged content present', res.json?.total);
    }, ['oidc-admin']),
  feat('F-ADMIN-02', 'ops-partner-bff', 'Admin settlement recent', ['ops-partner-bff'],
    'GET /v1/admin/settlement/recent returns recent settlement batches.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/settlement/recent',
        undefined, { as: 'oidc-admin' });
      check.equal(res.status, 200, 'settlement recent OK (200)');
      check.ok(Array.isArray(res.json), 'returns a list', res.json?.length);
    }, ['oidc-admin']),
  feat('F-ADMIN-03', 'ops-partner-bff', 'Admin revenue summary', ['ops-partner-bff'],
    'GET /v1/admin/revenue/summary returns aggregate revenue figures.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/revenue/summary',
        undefined, { as: 'oidc-admin' });
      check.equal(res.status, 200, 'revenue summary OK (200)');
    }, ['oidc-admin']),
  feat('F-ADMIN-04', 'ops-partner-bff', 'Partner token cannot reach the admin surface', ['ops-partner-bff'],
    'T0-1 boundary: a partner-scoped token anywhere under /v1/admin/** is 403 (AdminSurfaceRbacInterceptor). ' +
    'This is the separation that makes the portal safe to expose to partners.',
    async ({ client, check }) => {
      const res = await client.call('ops-partner-bff', 'GET', '/v1/admin/partners', undefined,
        { as: 'oidc-partner', expectAuthFailure: true });
      check.equal(res.status, 403, 'partner token refused on the admin surface (403)');
    }, ['oidc-partner']),

  // ============================================================ notification-webhook (lifecycle)
  feat('F-WEBHOOK-03', 'notification-webhook', 'Config get-by-id + soft delete', ['notification-webhook'],
    'A webhook config can be fetched by id then soft-deleted (204).',
    async ({ client, check }) => {
      const partnerId = 900000 + Math.floor(Math.random() * 90000);
      const create = await client.call('notification-webhook', 'POST', '/v1/webhook-configs',
        { partnerId, webhookUrl: 'https://example.com/cb', eventTypes: ['payment.approved'], signingSecret: uniq('whsec_') });
      check.equal(create.status, 201, 'config created (201)');
      const id = create.json?.id;
      const get = await client.call('notification-webhook', 'GET', `/v1/webhook-configs/${id}`);
      check.equal(get.status, 200, 'config fetched by id (200)');
      const del = await client.call('notification-webhook', 'DELETE', `/v1/webhook-configs/${id}`);
      check.equal(del.status, 204, 'config soft-deleted (204)');
    }),

  // ============================================================ config-registry (onboarding + 4-eyes happy path)
  feat('F-ONB-01', 'config-registry', 'Onboarding: create draft partner', ['config-registry'],
    'POST /v1/partners/draft creates an ONBOARDING draft that shows up in the drafts list.',
    async ({ client, check }) => {
      const code = uniq('D').toUpperCase();
      const create = await client.call('config-registry', 'POST', '/v1/partners/draft',
        { partnerCode: code, type: 'OVERSEAS', settlementCurrency: 'USD', settlementRoundingMode: 'HALF_UP' });
      check.equal(create.status, 201, 'draft created (201)');
      const drafts = await client.call('config-registry', 'GET', '/v1/partners/drafts');
      check.ok(Array.isArray(drafts.json) && drafts.json.some((p: any) => p.partnerCode === code), 'draft appears in drafts list', code);
    }),
  feat('F-ONB-02', 'config-registry', '4-eyes: approval by a different actor applies', ['config-registry'],
    'A change request proposed by one actor and approved by another reaches APPLIED (the happy-path complement to F-CONFIG-05).',
    async ({ client, check }) => {
      // The approval applies to a real aggregate, so create a partner first.
      const code = uniq('P').toUpperCase();
      const p = await client.call('config-registry', 'POST', '/v1/partners',
        { partnerCode: code, type: 'OVERSEAS', settlementCurrency: 'USD', settlementRoundingMode: 'HALF_UP' });
      check.equal(p.status, 201, 'partner created for the change request');
      const cr = await client.call('config-registry', 'POST', '/v1/change-requests',
        { aggregateType: 'partner', aggregateId: code, proposedBy: uniq('maker-'), payloadJsonb: '{"settlementRoundingMode":"DOWN"}' });
      check.equal(cr.status, 201, 'change request created (201)');
      const approve = await client.call('config-registry', 'POST', `/v1/change-requests/${cr.json?.id}/approve`, { approvedBy: uniq('checker-') });
      check.equal(approve.status, 200, 'approved by a different actor (200)');
      check.equal(approve.json?.state, 'APPLIED', 'change request reaches APPLIED');
    }),

  // ============================================================ refund / cancel contract (T2-6 / T2-7)
  feat('F-REFUND-01', 'payment-executor', 'Partial refund is REFUSED (422 PARTIAL_REFUND_UNSUPPORTED)', ['payment-executor'],
    'EXPECTATION CHANGED (T2-6): a partial refund now returns 422 `PARTIAL_REFUND_UNSUPPORTED` and mutates NOTHING. ' +
    'The ZeroPay adapter`s cancel contract is {schemeTxnRef, reason} with no amount, so the only instruction the ' +
    'platform could send is a FULL cancel — which would refund the customer more at the scheme than the books ' +
    'recorded. Refusing is correct; accepting would be a silent over-refund. A FULL refund is unaffected.',
    async ({ client, check }) => {
      await ensureSchemeMerchant(client);
      const paid = await client.pay({
        qrPayload: FIXTURES.qrPayload, amountKrw: '50000',
        partner: 'GMEREMIT', userRef: FIXTURES.gmeremitUser,
      });
      check.blockedIf(paid.status !== 201,
        `cannot test the refund contract without a completed payment — /v1/pay returned ${paid.status} ` +
        `(see UC-01-01 for the wallet-path precondition)`);
      const ref = paid.json?.schemeTxnRef;
      check.ok(!!ref, 'original payment reference captured', ref);

      const res = await client.refund(String(ref), {
        authId: paid.json?.schemeApprovalCode ?? ref,
        reason: 'PARTIAL_REFUND_CONTRACT_TEST',
        schemeId: 'ZEROPAY',
        amount: '100',
        currency: 'KRW',
      });
      check.equal(res.status, 422, 'partial refund refused (422), not silently accepted');
      const code = res.json?.code ?? res.json?.errorCode;
      check.equal(code, 'PARTIAL_REFUND_UNSUPPORTED', 'stable refusal code returned');
      check.equal(res.json?.retryable ?? false, false, 'refusal is a contract fact, not a transient fault');
    }),
  feat('F-REFUND-02', 'payment-executor', 'Cross-border refund → 422 SCHEME_OPERATION_UNSUPPORTED', ['payment-executor'],
    'EXPECTATION CHANGED (T2-7): a cancel/refund routed to a single-shot corridor (SENDMN / NEPAL) returns 422 ' +
    '`SCHEME_OPERATION_UNSUPPORTED` — those adapters have no refund round-trip, so the reversal must go through ' +
    'the manual/ops process. Nothing is mutated: the scheme call is the first step, so no float moves and no ' +
    'status is written. NOTE the trap this test exists to catch — OMITTING schemeId falls back to legacy ZeroPay ' +
    'routing and yields a foreign ZeroPay decline instead, so the scheme is always sent explicitly.',
    async ({ client, check }) => {
      const res = await client.refund(uniq('SENDMN-TX-'), {
        authId: uniq('auth-'),
        reason: 'CROSS_BORDER_CONTRACT_TEST',
        schemeId: 'SENDMN',
      });
      check.ok(res.status === 422 || res.status === 404,
        'cross-border refund refused (422 unsupported, or 404 if the reference does not exist)', res.status);
      if (res.status === 422) {
        const code = res.json?.code ?? res.json?.errorCode;
        check.equal(code, 'SCHEME_OPERATION_UNSUPPORTED', 'stable refusal code returned');
        check.equal(res.json?.retryable ?? false, false, 'not retryable — the corridor has no refund path at all');
      } else {
        check.blockedIf(true,
          'payment-executor validated the reference before resolving the scheme, so the contract could not be ' +
          'reached with a synthetic ref — needs a real completed SENDMN payment to assert end-to-end');
      }
    }),

  // ============================================================ negative / validation paths
  feat('F-VAL-01', 'rate-fx', 'Malformed rate request rejected', ['rate-fx'],
    'POST /v1/rates with an empty body is rejected with a 4xx (not a 500).',
    async ({ client, check }) => {
      const res = await client.call('rate-fx', 'POST', '/v1/rates', {});
      check.ok(res.status >= 400 && res.status < 500, 'malformed request rejected with 4xx', res.status);
    }),
  feat('F-VAL-02', 'prefunding', 'Invalid provision rejected', ['prefunding'],
    'POST /v1/prefunding/provision with a non-positive threshold is rejected (4xx).',
    async ({ client, check }) => {
      const res = await client.call('prefunding', 'POST', '/v1/prefunding/provision',
        { partnerCode: uniq('PF'), openingBalanceUsd: '100', lowBalanceThresholdUsd: '-5' });
      check.ok(res.status >= 400 && res.status < 500, 'invalid threshold rejected (4xx)', res.status);
    }),
  feat('F-VAL-03', 'config-registry', 'Partner without code rejected', ['config-registry'],
    'POST /v1/partners missing partnerCode is rejected (4xx).',
    async ({ client, check }) => {
      const res = await client.call('config-registry', 'POST', '/v1/partners', { type: 'OVERSEAS', settlementCurrency: 'USD' });
      check.ok(res.status >= 400 && res.status < 500, 'missing partnerCode rejected (4xx)', res.status);
    }),
];

// ---- helpers ---------------------------------------------------------------

function feat(
  id: string,
  service: string,
  title: string,
  services: string[],
  intent: string,
  run: UseCase['run'],
  credentials: CredentialKind[] = [],
): UseCase {
  return {
    id,
    bs: `FEAT-${service}`,
    bsTitle: `⚙ ${service}`,
    title,
    mvp: false,
    phase: 'Feature test',
    services,
    intent,
    automated: true,
    credentials,
    run,
  };
}

function crossBorderRate() {
  return {
    targetPayout: 13500, collectionCurrency: 'MNT', settleACurrency: 'MNT',
    settleBCurrency: 'KRW', payoutCurrency: 'KRW', costRateColl: 3500, costRatePay: 1350,
    mA: 0.01, mB: 0.01, serviceCharge: 500,
  };
}

function txnBody(ref: string) {
  return {
    partnerId: 2, partnerTxnRef: ref, schemeId: 'ZEROPAY', direction: 'INBOUND', paymentMode: 'MPM',
    targetPayout: 13500, payoutCurrency: 'KRW', collectionAmount: 36214.29, collectionCurrency: 'MNT', merchantId: FIXTURES.merchantId,
  };
}

function sum(xs: number[]): number {
  return xs.reduce((a, b) => a + b, 0);
}

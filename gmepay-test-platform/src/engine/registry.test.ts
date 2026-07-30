/**
 * Static assertions over the registry itself. These are the strongest verification
 * available without booting the fleet: they prove the matrix declares the right
 * credentials, no longer contains the retired bypasses, and stays internally
 * consistent.
 *
 * Run with: npm test
 */
import { strict as assert } from 'node:assert';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { USE_CASES, findUseCase } from './registry';
import { buildPlan, planSummary } from '../plan';

const SRC = join(import.meta.dirname, '..');
const source = (rel: string) => readFileSync(join(SRC, rel), 'utf8');

test('every use case id is unique', () => {
  const seen = new Set<string>();
  for (const uc of USE_CASES) {
    assert.ok(!seen.has(uc.id), `duplicate use case id: ${uc.id}`);
    seen.add(uc.id);
  }
});

test('findUseCase resolves a known id and rejects an unknown one', () => {
  assert.ok(findUseCase('UC-01-01'));
  assert.equal(findUseCase('UC-NOPE-99'), undefined);
});

test('the retired dev-login bypass is gone from the whole suite', () => {
  // The BFF deleted POST /v1/auth/login and password=demo. A test still POSTing
  // credentials to it expecting a 200 would be asserting a reintroduced bypass.
  for (const rel of ['usecases/features.ts', 'engine/registry.ts', 'engine/testkit.ts']) {
    const text = source(rel);
    assert.ok(
      !/password:\s*['"]demo['"]/.test(text) || rel === 'usecases/features.ts',
      `${rel} must not authenticate with password=demo`,
    );
  }
  // F-BFF-01 is allowed to SEND password=demo, because its purpose is to prove the
  // endpoint is gone. What it must never do is assert success.
  const bff = USE_CASES.find((u) => u.id === 'F-BFF-01');
  assert.ok(bff, 'F-BFF-01 must still exist as the deleted-endpoint assertion');
  assert.match(bff!.intent, /DELETED|EXPECTATION CHANGED/);
});

test('the retired stub partner credentials are not hardcoded anywhere', () => {
  for (const rel of ['engine/testkit.ts', 'engine/credentials.ts', 'config.ts']) {
    const text = source(rel);
    // They may be NAMED in a comment explaining why they are dead, but must never be
    // assigned as a value the tester would actually present.
    assert.ok(
      !/createHmac\([^)]*['"]sk_test_xyz['"]/.test(text),
      `${rel} must not sign with the retired stub secret`,
    );
    assert.ok(
      !/'X-API-Key':\s*['"]pk_test_abc['"]/.test(text),
      `${rel} must not present the retired stub key`,
    );
  }
});

test('the ignored header-identity helper is gone', () => {
  // rbacHeaders() stamped X-Gme-Principal-Id + X-Gme-Permissions, which no longer
  // authorize anything. Keeping it would invite writing new tests that cannot pass.
  // It may still be NAMED in a comment explaining the removal — what must not exist is
  // a definition or a call.
  assert.ok(
    !/export function rbacHeaders/.test(source('engine/testkit.ts')),
    'rbacHeaders must not be exported any more',
  );
  const features = source('usecases/features.ts');
  assert.ok(!/\brbacHeaders\s*\(/.test(features), 'no case may still call rbacHeaders');
  assert.ok(!/^import .*rbacHeaders/m.test(features), 'no case file may still import rbacHeaders');
});

test('no credential value is hardcoded — everything comes from the environment', () => {
  const cfg = source('config.ts');
  // Each credential must be read via env(); a literal default would be a committed secret.
  for (const v of [
    'GMEPAY_INTERNAL_AUTH_SECRET',
    'GMEPAY_ADMIN_TOKEN',
    'GMEPAY_PARTNER_TOKEN',
    'GMEPAY_PARTNER_API_KEY',
    'GMEPAY_PARTNER_HMAC_SECRET',
  ]) {
    assert.match(cfg, new RegExp(`env\\('${v}'\\)`), `${v} must be read from the environment`);
    assert.ok(
      !new RegExp(`env\\('${v}'\\)\\s*\\?\\?`).test(cfg),
      `${v} must have NO fallback default — a default here is a committed credential`,
    );
  }
});

// NOTE: the test runner transpiles TypeScript before these functions are stringified,
// which normalises quote style and strips whitespace. Every source assertion below must
// therefore be quote- and space-agnostic, or it silently matches nothing and passes
// vacuously. `str()` builds a matcher for a string literal in any quoting style.
const str = (literal: string) => `[\`'"]${literal.replace(/[/${}()|[\]\\.*+?^]/g, '\\$&')}`;

test('the source-matching helpers actually match transpiled output', () => {
  // A guard for the guards: if this stops matching, every source assertion below has
  // quietly stopped testing anything.
  const prefund = USE_CASES.find((u) => u.id === 'F-PREFUND-01');
  assert.ok(prefund?.run);
  const body = String(prefund!.run);
  assert.match(body, new RegExp(str('prefunding')), 'service-name matcher must work');
  assert.match(body, /as\s*:\s*['"]internal['"]/, 'credential matcher must work');
});

test('every case touching a gated surface declares the credential it needs', () => {
  // Path prefixes that the 2026-07-28 hardening put behind a credential, mapped to
  // the credential that opens them. Read straight off each service's path-patterns.
  const gated: Array<{ needle: RegExp; credential: string; why: string }> = [
    { needle: new RegExp(`${str('prefunding')}\\s*,\\s*['"](GET|POST|PUT)['"]`), credential: 'internal', why: 'prefunding gates /v1/prefunding/** and /internal/**' },
    { needle: new RegExp(str('/internal/')), credential: 'internal', why: 'every /internal/** surface is gated' },
    { needle: new RegExp(str('/v1/rbac/')), credential: 'internal', why: 'auth-identity gates /v1/rbac/**' },
    { needle: new RegExp(str('/v1/approvals')), credential: 'internal', why: 'auth-identity gates /v1/approvals/**' },
    { needle: new RegExp(str('/v1/kyb/screen')), credential: 'internal', why: 'kyb-adapter gates /v1/kyb/screen' },
    { needle: new RegExp(str('/v1/admin/')), credential: 'oidc-admin', why: 'the BFF admin surface needs an operator token' },
  ];

  let checked = 0;
  for (const uc of USE_CASES) {
    if (!uc.run || uc.unsupportedReason) continue;
    const body = String(uc.run);
    const declared = new Set<string>(uc.credentials ?? []);

    for (const g of gated) {
      if (!g.needle.test(body)) continue;
      // A case may deliberately probe the surface UNAUTHENTICATED to prove the gate
      // exists; those set expectAuthFailure instead of declaring a credential.
      if (/expectAuthFailure/.test(body)) continue;
      // config-registry's admin lifecycle surface is not JWT-gated (its own lib gate is
      // off — it declares a secret but never sets internal-auth.enabled=true).
      if (g.credential === 'oidc-admin' && new RegExp(str('config-registry')).test(body)) continue;
      checked++;
      assert.ok(
        declared.has(g.credential),
        `${uc.id} calls a gated surface but does not declare '${g.credential}' — ${g.why}`,
      );
    }
  }
  assert.ok(checked > 10, `expected this rule to bite on many cases, it matched ${checked}`);
});

test('a declared credential is actually presented in the case body', () => {
  // Convenience wrappers on GmePayClient set the credential internally, so a case using
  // one presents it without an explicit `as:`. They are listed here so the check stays
  // strict for every other call.
  const WRAPPERS_PRESENTING_INTERNAL = ['prefundingBalance'];

  for (const uc of USE_CASES) {
    if (!uc.run) continue;
    const body = uc.run.toString();
    for (const cred of uc.credentials ?? []) {
      if (cred === 'partner-key') {
        assert.match(body, /gatewayHeaders/, `${uc.id} declares partner-key but never signs a request`);
        continue;
      }
      const explicit = new RegExp(`as\\s*:\\s*['"]${cred}['"]`).test(body);
      const viaWrapper =
        cred === 'internal' && WRAPPERS_PRESENTING_INTERNAL.some((w) => body.includes(`.${w}(`));
      assert.ok(
        explicit || viaWrapper,
        `${uc.id} declares '${cred}' but never presents it (no as: '${cred}' and no wrapper that does)`,
      );
    }
  }
});

test('portal cases address a real partner code, not the old literal', () => {
  // The old hardcoded literal was never a config-registry partner code. PartnerDirectory
  // now resolves the code to a numeric surrogate and fails closed on an unknown one, so
  // that literal can only ever produce an error. It may appear in a comment explaining
  // the change; what must not exist is a request path built from it.
  const text = source('usecases/features.ts');
  assert.ok(
    !/\/v1\/portal\/partner_test_001/.test(text),
    'no portal request may still address the retired literal partner id',
  );
  assert.match(text, /PORTAL_PARTNER_CODE/, 'portal cases must use the configurable partner code');
  // Every portal path must be built from the configurable code.
  for (const uc of USE_CASES) {
    if (!uc.run) continue;
    const body = uc.run.toString();
    if (!body.includes('/v1/portal/')) continue;
    assert.match(body, /PORTAL_PARTNER_CODE|GMEREMIT|SENDMN/, `${uc.id} must address a real partner code`);
  }
});

test('expectations that changed are labelled so the diff is auditable', () => {
  const changed = USE_CASES.filter((u) => /EXPECTATION CHANGED/.test(u.intent));
  // The four behaviours the platform deliberately changed, plus the deleted login.
  const ids = changed.map((u) => u.id);
  for (const expected of ['F-BFF-01', 'F-KYB-01', 'F-KYB-03', 'F-REFUND-01', 'F-REFUND-02']) {
    assert.ok(ids.includes(expected), `${expected} must be labelled EXPECTATION CHANGED`);
  }
  assert.ok(changed.length >= 10, `expected many relabelled cases, found ${changed.length}`);
});

test('the intended refusal codes are asserted, not the old success behaviour', () => {
  const text = source('usecases/features.ts');
  for (const code of [
    'PARTIAL_REFUND_UNSUPPORTED',
    'SCHEME_OPERATION_UNSUPPORTED',
    'SANCTIONS_NOT_SCREENED',
  ]) {
    assert.ok(text.includes(code), `${code} must be asserted somewhere`);
  }
  // The Nepal corridor refusal is recorded in the registry (no QR fixture to drive it).
  assert.match(source('engine/registry.ts'), /CORRIDOR_PRICING_NOT_CONFIGURED/);
});

test('deliberately withdrawn capabilities are UNSUPPORTED, not deleted and not failing', () => {
  const sandbox = findUseCase('UC-SANDBOX-E2E');
  assert.ok(sandbox, 'the withdrawn sandbox runner must stay in the matrix');
  assert.ok(sandbox!.unsupportedReason, 'it must carry an unsupportedReason');
  assert.match(sandbox!.unsupportedReason!, /404|INTENTIONALLY REMOVED/);
  assert.equal(sandbox!.run, undefined, 'an unsupported case must not execute');
});

// --- plan mode ---------------------------------------------------------------

test('plan mode covers every case and sends no HTTP', () => {
  const rows = buildPlan();
  assert.equal(rows.length, USE_CASES.length);
  const s = planSummary(rows);
  assert.equal(s.total, USE_CASES.length);
  assert.ok(s.unsupported >= 1, 'the withdrawn sandbox runner must show as unsupported');
});

test('plan mode reports the missing variable for each credential-dependent case', () => {
  const rows = buildPlan();
  const internalCases = rows.filter((r) => r.credentials.includes('internal'));
  assert.ok(internalCases.length > 5, 'many cases depend on the internal token');
  // Without the env var configured, each must name it. (If a developer has it set
  // locally the case is simply ready, which is equally correct.)
  for (const r of internalCases) {
    if (r.ready) continue;
    assert.ok(
      r.missing.includes('GMEPAY_INTERNAL_AUTH_SECRET'),
      `${r.id} should name GMEPAY_INTERNAL_AUTH_SECRET as missing`,
    );
  }
});

test('an unsupported case is never reported as ready', () => {
  for (const r of buildPlan()) {
    if (r.unsupportedReason) assert.equal(r.ready, false);
  }
});

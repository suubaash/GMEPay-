/**
 * Unit tests for the credential layer. These run WITHOUT the GMEPay+ fleet, Keycloak
 * or Docker — which matters, because the whole point of this layer is to be
 * diagnosable before anything is booted.
 *
 * Run with: npm test
 */
import { strict as assert } from 'node:assert';
import test from 'node:test';

import * as credentials from './credentials';
import * as testkit from './testkit';

/**
 * config.ts reads every credential through a getter, so the modules observe the live
 * environment and a plain env mutation is enough — no module-cache juggling. The token
 * cache is cleared each time so a bearer token from one block cannot leak into the next.
 */
function loadCredentials(env: Record<string, string | undefined>) {
  for (const [k, v] of Object.entries(env)) {
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  credentials.resetTokenCache();
  return { ...credentials, ...testkit };
}

const NO_CREDENTIALS = {
  GMEPAY_INTERNAL_AUTH_SECRET: undefined,
  GMEPAY_ADMIN_TOKEN: undefined,
  GMEPAY_ADMIN_USERNAME: undefined,
  GMEPAY_ADMIN_PASSWORD: undefined,
  GMEPAY_PARTNER_TOKEN: undefined,
  GMEPAY_PARTNER_USERNAME: undefined,
  GMEPAY_PARTNER_PASSWORD: undefined,
  GMEPAY_PARTNER_API_KEY: undefined,
  GMEPAY_PARTNER_HMAC_SECRET: undefined,
};

test('an unset credential reports NOT configured and names the env var to set', async () => {
  const c = await loadCredentials(NO_CREDENTIALS);

  const internal = c.credentialStatus('internal');
  assert.equal(internal.configured, false);
  assert.deepEqual(internal.missing, ['GMEPAY_INTERNAL_AUTH_SECRET']);

  const partnerKey = c.credentialStatus('partner-key');
  assert.equal(partnerKey.configured, false);
  assert.deepEqual(partnerKey.missing, [
    'GMEPAY_PARTNER_API_KEY',
    'GMEPAY_PARTNER_HMAC_SECRET',
  ]);

  const admin = c.credentialStatus('oidc-admin');
  assert.equal(admin.configured, false);
  assert.match(admin.missing.join(' '), /GMEPAY_ADMIN_TOKEN/);
});

test('a missing credential throws before any HTTP, naming the variable', async () => {
  const c = await loadCredentials(NO_CREDENTIALS);
  await assert.rejects(
    () => c.credentialHeaders('internal'),
    (e: Error) => {
      assert.equal(e.name, 'MissingCredentialError');
      assert.match(e.message, /GMEPAY_INTERNAL_AUTH_SECRET/);
      return true;
    },
  );
});

test('the internal token is presented in the X-Gme-Internal header', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_INTERNAL_AUTH_SECRET: 'unit-test-token',
  });
  const headers = await c.credentialHeaders('internal');
  assert.deepEqual(headers, { 'X-Gme-Internal': 'unit-test-token' });
  assert.equal(c.credentialStatus('internal').configured, true);
});

test('a blank/whitespace env var counts as unset (no accidental empty credential)', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_INTERNAL_AUTH_SECRET: '   ',
  });
  // A blank header is a 401 at the gate, so treating it as configured would produce
  // exactly the vague failure this repair exists to remove.
  assert.equal(c.credentialStatus('internal').configured, false);
});

test('a pre-minted bearer token is used verbatim, with no call to the IdP', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_ADMIN_TOKEN: 'header.payload.sig',
  });
  const headers = await c.credentialHeaders('oidc-admin');
  assert.equal(headers.Authorization, 'Bearer header.payload.sig');
});

test('password-grant config is accepted as a valid credential path', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_ADMIN_USERNAME: 'admin',
    GMEPAY_ADMIN_PASSWORD: 'x',
  });
  const status = c.credentialStatus('oidc-admin');
  assert.equal(status.configured, true);
  assert.deepEqual(status.missing, []);
  // The hint must state why client-credentials is not an option, so nobody wastes
  // time trying to use it against the seeded public PKCE clients.
  assert.match(status.hint, /no client-credentials grant/i);
});

test('no credential status ever leaks a secret VALUE — only variable names', async () => {
  const secret = 'super-secret-value-do-not-print';
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_INTERNAL_AUTH_SECRET: secret,
    GMEPAY_PARTNER_API_KEY: 'pk_real_key',
    GMEPAY_PARTNER_HMAC_SECRET: secret,
    GMEPAY_ADMIN_TOKEN: secret,
  });
  const dumped = JSON.stringify(c.allCredentialStatuses());
  assert.ok(!dumped.includes(secret), 'credential statuses must not contain secret values');
});

test('permissions claim parses as an array OR a CSV string (the BFF accepts both)', async () => {
  const c = await loadCredentials(NO_CREDENTIALS);
  assert.deepEqual(c.permissionsOf({ permissions: ['a.b', 'c.d'] }), ['a.b', 'c.d']);
  assert.deepEqual(c.permissionsOf({ permissions: 'a.b, c.d' }), ['a.b', 'c.d']);
  assert.deepEqual(c.permissionsOf({}), []);
  assert.deepEqual(c.permissionsOf(undefined), []);
});

test('partner_id claim is read with its documented fallbacks', async () => {
  const c = await loadCredentials(NO_CREDENTIALS);
  assert.equal(c.partnerIdOf({ partner_id: 'GMEREMIT' }), 'GMEREMIT');
  assert.equal(c.partnerIdOf({ partnerId: 'SENDMN' }), 'SENDMN');
  assert.equal(c.partnerIdOf({ tenant_id: 7 }), '7');
  assert.equal(c.partnerIdOf({}), undefined);
});

test('decodeClaims reads a JWT payload without verifying it', async () => {
  const c = await loadCredentials(NO_CREDENTIALS);
  const payload = Buffer.from(JSON.stringify({ sub: 'u1', permissions: ['ops:operate'] }))
    .toString('base64url');
  const claims = c.decodeClaims(`h.${payload}.s`);
  assert.equal(claims?.sub, 'u1');
  assert.deepEqual(c.permissionsOf(claims), ['ops:operate']);
  assert.equal(c.decodeClaims('not-a-jwt'), undefined);
});

// --- api-gateway edge signing ------------------------------------------------

test('gatewayHeaders refuses to sign with no configured partner credential', async () => {
  const c = await loadCredentials(NO_CREDENTIALS);
  assert.throws(
    () => c.gatewayHeaders('GET', '/v1/route?country=KR'),
    (e: Error) => {
      assert.equal(e.name, 'MissingCredentialError');
      assert.match(e.message, /GMEPAY_PARTNER_API_KEY/);
      return true;
    },
  );
});

test('gatewayHeaders sends the mandatory X-Nonce and X-Partner-Id, and never the dead stub key', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_PARTNER_API_KEY: 'pk_real_abc',
    GMEPAY_PARTNER_HMAC_SECRET: 'sk_real_xyz',
    GMEPAY_PARTNER_CODE: 'GMEREMIT',
  });
  const h = c.gatewayHeaders('GET', '/v1/route?country=KR');

  assert.equal(h['X-API-Key'], 'pk_real_abc');
  assert.equal(h['X-Partner-Id'], 'GMEREMIT');
  assert.ok(h['X-Nonce'], 'X-Nonce is mandatory — absent means HTTP 400 at the edge');
  assert.ok(h['X-Timestamp'], 'X-Timestamp is required for the ±300s skew window');
  assert.match(h['X-Signature'], /^[0-9a-f]{64}$/, 'lowercase hex HMAC-SHA256');

  // Regression guard: the retired published pair must never be reintroduced.
  const dumped = JSON.stringify(h);
  assert.ok(!dumped.includes('pk_test_abc'), 'must not present the retired stub key');
  assert.ok(!dumped.includes('sk_test_xyz'), 'must not sign with the retired stub secret');
});

test('every gatewayHeaders call uses a fresh nonce (reuse is a 401)', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_PARTNER_API_KEY: 'pk_real_abc',
    GMEPAY_PARTNER_HMAC_SECRET: 'sk_real_xyz',
  });
  const a = c.gatewayHeaders('GET', '/v1/route');
  const b = c.gatewayHeaders('GET', '/v1/route');
  assert.notEqual(a['X-Nonce'], b['X-Nonce']);
});

test('the signature covers method, path and body (canonical string is order-sensitive)', async () => {
  const c = await loadCredentials({
    ...NO_CREDENTIALS,
    GMEPAY_PARTNER_API_KEY: 'pk_real_abc',
    GMEPAY_PARTNER_HMAC_SECRET: 'sk_real_xyz',
  });
  const get = c.gatewayHeaders('GET', '/v1/a');
  const post = c.gatewayHeaders('POST', '/v1/a');
  const other = c.gatewayHeaders('GET', '/v1/b');
  const body = c.gatewayHeaders('GET', '/v1/a', '{"x":1}');
  const sigs = new Set([get['X-Signature'], post['X-Signature'], other['X-Signature'], body['X-Signature']]);
  assert.equal(sigs.size, 4, 'method, path and body must each change the signature');
});

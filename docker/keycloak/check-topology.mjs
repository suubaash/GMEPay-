#!/usr/bin/env node
/**
 * OIDC topology guard (gap register T1-2).
 *
 * T1-2 was not one bug — it was four files disagreeing about realm, client id and
 * port, in three different directions, with nothing to catch the drift. This
 * script is that check: it parses the realm seed, docker-compose.yml, every Helm
 * values file and both SPAs' oidc.js defaults + .env files, and asserts they
 * describe ONE identity topology.
 *
 * Run (no dependencies, no servers):
 *   node docker/keycloak/check-topology.mjs
 *
 * Exit 0 = consistent, 1 = a mismatch (printed with the offending file).
 *
 * The YAML reading is deliberately regex-based on the specific keys we care
 * about: the repo has no js-yaml dependency at the root, and a full parse is not
 * needed to compare a handful of scalar strings.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const read = (rel) => readFileSync(path.join(repo, rel), 'utf8');

const REALM = 'gmepay';
const ADMIN_CLIENT = 'admin-ui';
const PORTAL_CLIENT = 'partner-portal-ui';
const LOCAL_ISSUER = `http://localhost:8097/realms/${REALM}`;
const JWKS_PATH = `/realms/${REALM}/protocol/openid-connect/certs`;

/** Strip comment lines so a check never matches prose that only DISCUSSES a value. */
const codeOnly = (body) =>
  body
    .split('\n')
    .filter((l) => !/^\s*(\*|\/\/|#)/.test(l))
    .join('\n');

const failures = [];
const checks = [];
function check(label, ok, detail) {
  checks.push({ label, ok, detail });
  if (!ok) failures.push(`${label}${detail ? ` — ${detail}` : ''}`);
}

// ---------------------------------------------------------------------------
// 1. Realm seed is the source of truth
// ---------------------------------------------------------------------------
const realm = JSON.parse(read('docker/keycloak/realm-gmepay.json'));
check('realm seed parses + names realm `gmepay`', realm.realm === REALM, `got ${realm.realm}`);

const clientsById = Object.fromEntries(realm.clients.map((c) => [c.clientId, c]));
for (const id of [ADMIN_CLIENT, PORTAL_CLIENT]) {
  const c = clientsById[id];
  check(`seed declares client \`${id}\``, Boolean(c));
  if (!c) continue;
  check(`\`${id}\` is a PUBLIC client`, c.publicClient === true,
    'a browser SPA cannot present a client_secret (this was the invalid_client half of T1-2)');
  check(`\`${id}\` carries no secret`, c.secret === undefined,
    'a committed secret shipped to the browser is not a secret');
  check(`\`${id}\` enforces PKCE S256`,
    c.attributes?.['pkce.code.challenge.method'] === 'S256');
  const mappers = (c.protocolMappers ?? []).map((m) => m.config?.['claim.name']);
  // TokenClaims.java requires both of these; without them every caller is
  // authenticated-but-403 / cannot be partner-scoped at all.
  check(`\`${id}\` maps the \`permissions\` claim`, mappers.includes('permissions'));
  check(`\`${id}\` maps the \`partner_id\` claim`, mappers.includes('partner_id'));
  check(`\`${id}\` includes the built-in \`roles\` scope (realm_access.roles)`,
    (c.defaultClientScopes ?? []).includes('roles'));
}

// Every partner user must carry a partner_id that a seeded partner actually has.
const seededPartners = new Set(
  [...read('services/config-registry/src/main/java/com/gme/pay/registry/partner/PartnerSeeder.java')
    .matchAll(/Partner\.of\("([A-Z0-9_]+)"/g)].map((m) => m[1]),
);
check('PartnerSeeder codes were found', seededPartners.size > 0, [...seededPartners].join(','));

for (const u of realm.users ?? []) {
  const roles = u.realmRoles ?? [];
  const partnerId = u.attributes?.partner_id?.[0];
  if (roles.includes('PARTNER_USER')) {
    check(`user \`${u.username}\` has a partner_id`, Boolean(partnerId),
      'a portal user without the claim 403s on every /v1/portal/{partnerId}/** call');
    check(`user \`${u.username}\` partner_id is a REAL seeded partner`,
      seededPartners.has(partnerId), `${partnerId} not in [${[...seededPartners]}]`);
  }
  if (roles.includes('OPERATOR')) {
    check(`operator \`${u.username}\` has permissions`,
      (u.attributes?.permissions ?? []).length > 0,
      'an operator with no permissions claim is authenticated-but-403 everywhere');
  }
}

// ---------------------------------------------------------------------------
// 2. docker-compose.yml
// ---------------------------------------------------------------------------
const compose = read('docker-compose.yml');
check('compose maps Keycloak to host port 8097', /ports:\s*\["8097:8080"\]/.test(compose));
check('compose pins KC_HOSTNAME_URL to the browser-facing base URL',
  /KC_HOSTNAME_URL:\s*\$\{KC_PUBLIC_URL:-http:\/\/localhost:8097\}/.test(compose),
  'without it Keycloak stamps `iss` from whatever Host header it saw');

const issuerLines = [...compose.matchAll(/OIDC_ISSUER_URI:\s*(\S+)/g)].map((m) => m[1]);
check('compose sets OIDC_ISSUER_URI for both resource servers (bff + gateway)',
  issuerLines.length >= 2, `found ${issuerLines.length}`);
check('every compose issuer uses the same KC_PUBLIC_URL knob + realm',
  issuerLines.every((v) => v === '${KC_PUBLIC_URL:-http://localhost:8097}/realms/gmepay'),
  issuerLines.join(' | '));

const jwks = [...compose.matchAll(/SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI:\s*(\S+)/g)]
  .map((m) => m[1]);
check('compose pins JWKS in-network for both resource servers', jwks.length >= 2, `found ${jwks.length}`);
check('compose JWKS URIs are container-reachable (keycloak:8080)',
  jwks.every((v) => v === `http://keycloak:8080${JWKS_PATH}`), jwks.join(' | '));
check('compose does NOT disable RBAC enforcement',
  !/GMEPAY_OPS_RBAC_ENFORCE:\s*["']?false/.test(compose));

// ---------------------------------------------------------------------------
// 2b. run-fleet.ps1 (host fleet) — the T1-2 residual
// ---------------------------------------------------------------------------
// ops-partner-bff and api-gateway default their issuer to :8090, which is
// scheme-adapter-zeropay's port here. Compose sets OIDC_ISSUER_URI explicitly;
// the host fleet did not, so a host-run BFF 401'd every request. One export in
// the script reaches every child JVM (Start-Process inherits its environment).
const fleetScript = read('run-fleet.ps1');
const fleetCode = fleetScript
  .split('\n')
  .filter((l) => !/^\s*#/.test(l))
  .join('\n');
const fleetIssuer = fleetCode.match(/\$env:OIDC_ISSUER_URI\s*=\s*'([^']+)'/)?.[1];
check('run-fleet.ps1 pins OIDC_ISSUER_URI for the host fleet', Boolean(fleetIssuer),
  'without it a host-run ops-partner-bff falls back to the stale :8090 Java default');
check(`run-fleet.ps1 issuer === ${LOCAL_ISSUER}`, fleetIssuer === LOCAL_ISSUER,
  `got ${fleetIssuer}`);

// ---------------------------------------------------------------------------
// 3. SPA defaults + env files
// ---------------------------------------------------------------------------
for (const [app, client] of [['admin-ui', ADMIN_CLIENT], ['partner-portal-ui', PORTAL_CLIENT]]) {
  const oidc = read(`apps/${app}/src/api/oidc.js`);
  const url = oidc.match(/DEFAULT_KEYCLOAK_URL\s*=\s*'([^']+)'/)?.[1];
  const cid = oidc.match(/DEFAULT_CLIENT_ID\s*=\s*'([^']+)'/)?.[1];
  check(`${app} oidc.js default issuer === ${LOCAL_ISSUER}`, url === LOCAL_ISSUER, `got ${url}`);
  check(`${app} oidc.js default client === ${client}`, cid === client, `got ${cid}`);
  check(`${app} oidc.js sends no client_secret`, !/client_secret/.test(codeOnly(oidc)));

  for (const envFile of ['.env.example', '.env.local']) {
    let env;
    try {
      env = read(`apps/${app}/${envFile}`);
    } catch {
      continue; // .env.local is gitignored — absent on a fresh clone
    }
    const kcUrl = env.match(/^NEXT_PUBLIC_KEYCLOAK_URL=(.+)$/m)?.[1]?.trim();
    const kcId = env.match(/^NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=(.+)$/m)?.[1]?.trim();
    check(`${app}/${envFile} realm URL matches the seed`, kcUrl === LOCAL_ISSUER, `got ${kcUrl}`);
    check(`${app}/${envFile} client id matches the seed`, kcId === client, `got ${kcId}`);
    check(`${app}/${envFile} sets BFF_PROXY_TARGET`, /^BFF_PROXY_TARGET=/m.test(env));
    // A NEXT_PUBLIC_ base URL flips the browser into cross-origin calls the BFF
    // cannot serve (no CORS config anywhere in the repo).
    check(`${app}/${envFile} does NOT set NEXT_PUBLIC_BFF_BASE_URL`,
      !/^NEXT_PUBLIC_BFF_BASE_URL=/m.test(env));
  }
}

// ---------------------------------------------------------------------------
// 4. Helm values (base + every overlay)
// ---------------------------------------------------------------------------
for (const f of ['values.yaml', 'values-onprem.yaml', 'values-aws.yaml', 'values-azure.yaml']) {
  const y = read(`deploy/helm/gmepay/${f}`);
  const issuers = [...y.matchAll(/^\s*(?:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI|OIDC_ISSUER_URI):\s*(\S+)/gm)]
    .map((m) => m[1]);
  check(`${f} declares both issuer keys`, issuers.length === 2, `found ${issuers.length}`);
  check(`${f} issuer keys agree with each other`,
    new Set(issuers).size === 1, issuers.join(' | '));
  check(`${f} issuer targets realm /${REALM}`,
    issuers.every((v) => v.endsWith(`/realms/${REALM}`)), issuers.join(' | '));
  // The issuer must be the BROWSER-facing URL: an in-cluster service URL can
  // never equal the `iss` of a token a browser obtained through the ingress.
  check(`${f} issuer is not an in-cluster service URL`,
    issuers.every((v) => !/^http:\/\/keycloak:\d+/.test(v)), issuers.join(' | '));

  const uiUrls = [...y.matchAll(/^\s*NEXT_PUBLIC_KEYCLOAK_URL:\s*(\S+)/gm)].map((m) => m[1]);
  for (const u of uiUrls) {
    check(`${f} UI realm URL === the issuer`, issuers.includes(u), `${u} vs ${issuers.join('|')}`);
  }
  const uiClients = [...y.matchAll(/^\s*NEXT_PUBLIC_KEYCLOAK_CLIENT_ID:\s*(\S+)/gm)].map((m) => m[1]);
  for (const c of uiClients) {
    check(`${f} UI client \`${c}\` exists in the seed`, Boolean(clientsById[c]));
  }
  check(`${f} does not reference the phantom gmepay-partners realm`,
    !y.includes('gmepay-partners'));
  check(`${f} does not set NEXT_PUBLIC_BFF_BASE_URL on a UI`,
    !/^\s*NEXT_PUBLIC_BFF_BASE_URL:/m.test(y));
}

// ---------------------------------------------------------------------------
// 5. No file anywhere in the owned set still names the phantom realm/clients
// ---------------------------------------------------------------------------
for (const f of [
  'docker-compose.yml',
  'docker/keycloak/realm-gmepay.json',
  'apps/admin-ui/src/api/oidc.js',
  'apps/partner-portal-ui/src/api/oidc.js',
]) {
  // The SPA modules explain the history in comments; only reject live values.
  const live = codeOnly(read(f));
  for (const phantom of ['gmepay-partners', 'gmepay-partner-ui', 'gmepay-admin-ui']) {
    check(`${f} has no live reference to \`${phantom}\``, !live.includes(phantom));
  }
}

// ---------------------------------------------------------------------------
for (const c of checks) {
  console.log(`${c.ok ? 'ok  ' : 'FAIL'}  ${c.label}${c.ok || !c.detail ? '' : ` — ${c.detail}`}`);
}
console.log(`\n${checks.length - failures.length}/${checks.length} checks passed`);
if (failures.length) {
  console.error(`\n${failures.length} MISMATCH(ES):\n- ${failures.join('\n- ')}`);
  process.exit(1);
}

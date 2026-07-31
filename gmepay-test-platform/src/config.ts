import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Load `.env` from the project root into process.env if present, without adding a
 * dependency. Existing environment variables always win, so CI can override the
 * file. `.env` is gitignored — credentials must never be committed.
 */
function loadDotEnv(): void {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const file = process.env.GMEPAY_ENV_FILE ?? join(root, '.env');
  if (!existsSync(file)) return;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    const key = trimmed.slice(0, eq).trim();
    let value = trimmed.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = value;
  }
}
loadDotEnv();

/** Read an env var, treating blank/whitespace as absent. */
function env(name: string): string | undefined {
  const v = process.env[name];
  if (v === undefined) return undefined;
  const t = v.trim();
  return t === '' ? undefined : t;
}

/**
 * GMEPay+ service topology — ports come straight from the platform's
 * `code/run-fleet.ps1` fleet definition. Override the host with GMEPAY_HOST
 * if you run the services on another machine.
 */
export const SERVICES: Record<string, number> = {
  'api-gateway': 18080,
  'config-registry': 18081,
  'transaction-mgmt': 18082,
  'merchant-qr-data': 18083,
  'payment-executor': 18084,
  'auth-identity': 18085,
  'notification-webhook': 18086,
  'reporting-compliance': 18087,
  prefunding: 18088,
  'qr-service': 18089,
  'scheme-adapter-zeropay': 18090,
  'smart-router': 18091,
  'revenue-ledger': 18092,
  'settlement-reconciliation': 18093,
  'ops-partner-bff': 18095,
  'kyb-adapter': 18098,
  'rate-fx': 18101,
  'sim-rate-provider': 9101,
  'sim-scheme': 9102,
  'sim-wallet': 9103,
  'sim-merchant': 9104,
  'sim-gmeremit': 9105,
};

export const HOST = process.env.GMEPAY_HOST ?? 'localhost';

export function baseUrl(service: string): string {
  const port = SERVICES[service];
  if (!port) throw new Error(`Unknown service '${service}' — not in config.ts`);
  return `http://${HOST}:${port}`;
}

// --- ZeroPay EMVCo QR builder ----------------------------------------------
// The ZeroPay parser requires the Merchant Account Info template (tag 29) to
// carry sub-tag 00=com.zeropay, 01=merchantId, 02=qr_code_id, plus a valid
// CRC-16/CCITT-FALSE checksum in tag 63. We build it here so tests use a
// well-formed payload (the .smoke fixture was missing sub-tag 02).
function tlv(tag: string, value: string): string {
  return tag + value.length.toString().padStart(2, '0') + value;
}

function crc16ccitt(input: string): string {
  let crc = 0xffff;
  for (let i = 0; i < input.length; i++) {
    crc ^= input.charCodeAt(i) << 8;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x8000) !== 0 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc.toString(16).toUpperCase().padStart(4, '0');
}

export function buildZeroPayQr(merchantId: string, qrCodeId: string): string {
  const mai = tlv('00', 'com.zeropay') + tlv('01', merchantId) + tlv('02', qrCodeId);
  const body =
    tlv('00', '01') + // payload format indicator
    tlv('52', '5999') + // MCC
    tlv('53', '410') + // currency = KRW (ISO-4217 numeric)
    tlv('58', 'KR') + // country
    tlv('59', 'Smoke Merchant') + // merchant name
    tlv('60', 'Seoul') + // city
    tlv('29', mai); // merchant account info (ZeroPay MAI)
  const withCrcTag = body + '6304';
  return withCrcTag + crc16ccitt(withCrcTag);
}

// A merchant pre-seeded in merchant-qr-data's InMemoryMerchantRepository (and
// matching the sim-scheme ZeroPay sandbox), so the full wallet path resolves.
const MERCHANT_ID = 'M0000000001';
const QR_CODE_ID = 'QR00000000000000001A';

/** Known-good test data; the QR is generated with a valid structure + CRC. */
export const FIXTURES = {
  qrPayload: buildZeroPayQr(MERCHANT_ID, QR_CODE_ID),
  merchantId: MERCHANT_ID,
  qrCodeId: QR_CODE_ID,
  gmeremitUser: 'gmeremit-user-001',
  sendmnUser: 'sendmn-user-001',
  sendmnPartnerCode: 'SENDMN',
};

export const API_PORT = Number(process.env.PORT ?? 4000);
export const HTTP_TIMEOUT_MS = Number(process.env.GMEPAY_TIMEOUT_MS ?? 8000);

// --- Identity provider (canonical Keycloak topology, T1-1) -------------------
// Realm `gmepay` on host port 8097. The browser issuer IS the token `iss`, so the
// same URL works for a direct token request. Both seeded SPA clients are PUBLIC +
// PKCE with serviceAccountsEnabled=false — there is NO client-credentials grant,
// so the only non-browser path is the password grant with a realm user (or a token
// minted elsewhere and passed in via GMEPAY_*_TOKEN).
const OIDC_ISSUER = env('GMEPAY_OIDC_ISSUER') ?? 'http://localhost:8097/realms/gmepay';

export const OIDC = {
  issuer: OIDC_ISSUER,
  tokenEndpoint:
    env('GMEPAY_OIDC_TOKEN_ENDPOINT') ?? `${OIDC_ISSUER}/protocol/openid-connect/token`,
  /** Public PKCE client used by admin-ui. */
  adminClientId: env('GMEPAY_OIDC_ADMIN_CLIENT_ID') ?? 'admin-ui',
  /** Public PKCE client used by partner-portal-ui. */
  partnerClientId: env('GMEPAY_OIDC_PARTNER_CLIENT_ID') ?? 'partner-portal-ui',
  timeoutMs: Number(env('GMEPAY_OIDC_TIMEOUT_MS') ?? 8000),
} as const;

/**
 * Every credential comes from the environment. There are intentionally NO secret
 * defaults and no `demo`-style bypass: an unset value makes the affected cases
 * report BLOCKED naming the variable, which is the diagnostic this tool exists for.
 *
 * These are GETTERS, not captured values, so the credential state always reflects the
 * live environment rather than whatever existed at module-load time.
 */
export const CREDENTIALS = {
  /** Pre-minted operator bearer token (skips the password grant entirely). */
  get adminToken() { return env('GMEPAY_ADMIN_TOKEN'); },
  get adminUsername() { return env('GMEPAY_ADMIN_USERNAME'); },
  get adminPassword() { return env('GMEPAY_ADMIN_PASSWORD'); },

  /** Pre-minted partner-scoped bearer token. */
  get partnerToken() { return env('GMEPAY_PARTNER_TOKEN'); },
  get partnerUsername() { return env('GMEPAY_PARTNER_USERNAME'); },
  get partnerPassword() { return env('GMEPAY_PARTNER_PASSWORD'); },

  /** Shared service-to-service token for the `X-Gme-Internal` header. */
  get internalToken() { return env('GMEPAY_INTERNAL_AUTH_SECRET'); },

  /** Real partner edge credentials (api-gateway). `pk_test_abc`/`sk_test_xyz` are dead. */
  get partnerApiKey() { return env('GMEPAY_PARTNER_API_KEY'); },
  get partnerHmacSecret() { return env('GMEPAY_PARTNER_HMAC_SECRET'); },
} as const;

/**
 * Partner business CODE the portal cases address. ops-partner-bff resolves the code
 * to config-registry's numeric surrogate via PartnerDirectory, and authorizes the
 * path against the token's `partner_id` claim — so this must match the seeded user's
 * claim (`GMEREMIT` for the seeded partner user, `SENDMN` for the other). The literal
 * this replaced was never a real partner code, so it now fails closed.
 */
export const PORTAL_PARTNER_CODE = env('GMEPAY_PORTAL_PARTNER_CODE') ?? 'GMEREMIT';

/** Partner code the api-gateway edge expects in `X-Partner-Id` (must match the resolved key). */
export function gatewayPartnerCode(): string {
  return env('GMEPAY_PARTNER_CODE') ?? PORTAL_PARTNER_CODE;
}

/**
 * Credential paths the GMEPay+ platform requires as of the 2026-07-28 security
 * hardening (branch `feat/exec-gap-closure-2026-07-28`).
 *
 * Before that change this tester authenticated with three things that no longer
 * grant anything:
 *   - `POST /v1/auth/login` + `password=demo` on ops-partner-bff   → DELETED
 *   - `X-Gme-Permissions` / `X-Partner-Id` / `X-Gme-Principal-Id`  → ignored
 *   - the published stub partner pair `pk_test_abc` / `sk_test_xyz` → 401
 *
 * It now needs three REAL credentials, every value supplied from the environment.
 * Nothing in this file carries a secret default: an unconfigured credential makes
 * the affected cases report BLOCKED naming the exact variable to set, which is far
 * more useful than a vague 401.
 *
 *   'oidc-admin'    Bearer JWT for ops-partner-bff `/v1/admin/**`   (platform operator)
 *   'oidc-partner'  Bearer JWT for ops-partner-bff `/v1/portal/**`  (partner-scoped)
 *   'internal'      `X-Gme-Internal` shared token for every gated `/internal/**`
 *                   surface (and prefunding's whole `/v1/prefunding/**` API,
 *                   auth-identity's `/v1/rbac/**` + `/v1/approvals/**`, and
 *                   kyb-adapter's `/v1/kyb/screen`)
 *   'partner-key'   real partner API key + HMAC secret for the api-gateway edge
 */
import { CREDENTIALS, OIDC } from '../config';

export type CredentialKind =
  | 'none'
  | 'oidc-admin'
  | 'oidc-partner'
  | 'internal'
  | 'partner-key';

/** Human label used in reports and the plan output. */
export const CREDENTIAL_LABEL: Record<CredentialKind, string> = {
  none: 'no credential (public surface)',
  'oidc-admin': 'OIDC bearer — platform operator',
  'oidc-partner': 'OIDC bearer — partner-scoped',
  internal: 'X-Gme-Internal shared token',
  'partner-key': 'partner API key + HMAC secret (api-gateway edge)',
};

/**
 * Raised when a test needs a credential the environment does not supply. The
 * runner maps this to BLOCKED — the tester's job is to say precisely what is
 * missing, not to fail obscurely.
 */
export class MissingCredentialError extends Error {
  constructor(public kind: CredentialKind, public missing: string[], public hint: string) {
    super(
      `Missing credential '${kind}' (${CREDENTIAL_LABEL[kind]}). ` +
        `Set ${missing.join(' + ')}. ${hint}`,
    );
    this.name = 'MissingCredentialError';
  }
}

/**
 * Raised when a credential WAS presented and the platform still rejected it.
 * Distinguishing this from MissingCredentialError is the whole point: one means
 * "you did not configure the tester", the other means "the platform said no".
 */
export class AuthRejectedError extends Error {
  constructor(
    public status: number,
    public service: string,
    public path: string,
    public kind: CredentialKind,
    public detail: string,
  ) {
    super(
      `HTTP ${status} from ${service} ${path} — presented ${CREDENTIAL_LABEL[kind]}. ` +
        `${detail}`,
    );
    this.name = 'AuthRejectedError';
  }
}

/** Raised when a token could not be obtained from the identity provider at all. */
export class TokenAcquisitionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TokenAcquisitionError';
  }
}

export interface CredentialStatus {
  kind: CredentialKind;
  label: string;
  configured: boolean;
  /** How it will be presented, e.g. `Authorization: Bearer …` */
  presentedAs: string;
  /** Env vars that are set (names only — never values). */
  suppliedBy: string[];
  /** Env vars still needed. */
  missing: string[];
  hint: string;
}

/** What the platform tells you when a credential is rejected, per credential. */
const REJECTION_HINTS: Record<CredentialKind, string> = {
  none: 'This surface is expected to be anonymous; a 401/403 means it was gated since this test was written.',
  'oidc-admin':
    'ops-partner-bff is an OAuth2 resource server (default-deny). A 401 with an EMPTY body = no/invalid token. ' +
    'A 403 = the token authenticated but its `permissions` claim lacks an admin permission ' +
    '(one of ops:operate, partner.activate, rbac.manage, approval.cfo_override, settlement.resolve_exception ' +
    'for writes; any platform-operator permission for reads). Check the realm has the `gmepay-permissions` mapper.',
  'oidc-partner':
    'A 401 = no/invalid token. A 403 = the token`s `partner_id` claim does not match the partner in the path ' +
    '(portal reads are scoped by claim, not by the X-Partner-Id header, which is now ignored).',
  internal:
    'The gated surface answers 401 with {"code":"UNAUTHORIZED","message":"internal service authentication required"}. ' +
    'The value must byte-match the target service`s GMEPAY_INTERNAL_AUTH_SECRET (compared trimmed, constant-time).',
  'partner-key':
    'The api-gateway edge resolves the key against auth-identity AND requires matching HMAC material in ' +
    '`gateway.partner-credentials.partners[]`. A key present in only one half fails: 401 INVALID_API_KEY. ' +
    'Also: X-Nonce is mandatory (absent → 400, reused → 401) and X-Partner-Id must match the resolved partner ' +
    '(mismatch → 403 PARTNER_ID_MISMATCH).',
};

export function credentialStatus(kind: CredentialKind): CredentialStatus {
  switch (kind) {
    case 'none':
      return {
        kind,
        label: CREDENTIAL_LABEL[kind],
        configured: true,
        presentedAs: '(nothing)',
        suppliedBy: [],
        missing: [],
        hint: REJECTION_HINTS[kind],
      };

    case 'internal': {
      const set = !!CREDENTIALS.internalToken;
      return {
        kind,
        label: CREDENTIAL_LABEL[kind],
        configured: set,
        presentedAs: 'X-Gme-Internal: <token>',
        suppliedBy: set ? ['GMEPAY_INTERNAL_AUTH_SECRET'] : [],
        missing: set ? [] : ['GMEPAY_INTERNAL_AUTH_SECRET'],
        hint:
          'Must equal the value the fleet was booted with. auth-identity, prefunding, ' +
          'scheme-adapter-zeropay and rate-fx refuse to START without it, so if they are up, a value exists.',
      };
    }

    case 'oidc-admin':
    case 'oidc-partner': {
      const t = kind === 'oidc-admin' ? CREDENTIALS.adminToken : CREDENTIALS.partnerToken;
      const u = kind === 'oidc-admin' ? CREDENTIALS.adminUsername : CREDENTIALS.partnerUsername;
      const p = kind === 'oidc-admin' ? CREDENTIALS.adminPassword : CREDENTIALS.partnerPassword;
      const tokenVar = kind === 'oidc-admin' ? 'GMEPAY_ADMIN_TOKEN' : 'GMEPAY_PARTNER_TOKEN';
      const userVar = kind === 'oidc-admin' ? 'GMEPAY_ADMIN_USERNAME' : 'GMEPAY_PARTNER_USERNAME';
      const passVar = kind === 'oidc-admin' ? 'GMEPAY_ADMIN_PASSWORD' : 'GMEPAY_PARTNER_PASSWORD';

      if (t) {
        return {
          kind,
          label: CREDENTIAL_LABEL[kind],
          configured: true,
          presentedAs: 'Authorization: Bearer <pre-minted token>',
          suppliedBy: [tokenVar],
          missing: [],
          hint: REJECTION_HINTS[kind],
        };
      }
      const ropc = !!(u && p);
      return {
        kind,
        label: CREDENTIAL_LABEL[kind],
        configured: ropc,
        presentedAs: ropc
          ? `Authorization: Bearer <token from ${OIDC.tokenEndpoint} via password grant as ${u}>`
          : 'Authorization: Bearer <unavailable>',
        suppliedBy: ropc ? [userVar, passVar] : [],
        missing: ropc ? [] : [`${tokenVar} (or ${userVar} + ${passVar})`],
        hint:
          `Realm ${OIDC.issuer}. Client ${kind === 'oidc-admin' ? OIDC.adminClientId : OIDC.partnerClientId} ` +
          `is PUBLIC + PKCE with serviceAccountsEnabled=false, so there is NO client-credentials grant — ` +
          `the only non-browser path is the password grant with a realm user, or a token you mint yourself ` +
          `and pass in ${tokenVar}. ` +
          REJECTION_HINTS[kind],
      };
    }

    case 'partner-key': {
      const ok = !!(CREDENTIALS.partnerApiKey && CREDENTIALS.partnerHmacSecret);
      const missing: string[] = [];
      if (!CREDENTIALS.partnerApiKey) missing.push('GMEPAY_PARTNER_API_KEY');
      if (!CREDENTIALS.partnerHmacSecret) missing.push('GMEPAY_PARTNER_HMAC_SECRET');
      return {
        kind,
        label: CREDENTIAL_LABEL[kind],
        configured: ok,
        presentedAs: 'X-API-Key + X-Timestamp + X-Nonce + X-Partner-Id + X-Signature (HMAC-SHA256)',
        suppliedBy: ok
          ? ['GMEPAY_PARTNER_API_KEY', 'GMEPAY_PARTNER_HMAC_SECRET', 'GMEPAY_PARTNER_CODE']
          : [],
        missing,
        hint:
          'The old published pair pk_test_abc / sk_test_xyz is gone — StubPartnerCredentialService was ' +
          'deleted from src/main, so those values now correctly 401. Provision a real key via ' +
          'auth-identity POST /internal/auth/keys (needs X-Gme-Internal), capture the one-time sk_… ' +
          'plaintext, and add the matching row to the gateway`s gateway.partner-credentials.partners[]. ' +
          REJECTION_HINTS[kind],
      };
    }
  }
}

export function allCredentialStatuses(): CredentialStatus[] {
  return (['oidc-admin', 'oidc-partner', 'internal', 'partner-key'] as CredentialKind[]).map(
    credentialStatus,
  );
}

/** Cached bearer tokens, keyed by credential kind, so a run mints at most one each. */
const tokenCache = new Map<CredentialKind, string>();

/** Test seam: forget any cached token (used by the unit tests). */
export function resetTokenCache(): void {
  tokenCache.clear();
}

/**
 * Obtain a bearer token. Prefers a pre-minted token from the environment (the CI
 * path — mint it however you like and hand it over); otherwise uses the OAuth2
 * password grant against the canonical realm, which is the only non-browser grant
 * the seeded public PKCE clients allow.
 */
export async function bearerToken(kind: 'oidc-admin' | 'oidc-partner'): Promise<string> {
  const cached = tokenCache.get(kind);
  if (cached) return cached;

  const status = credentialStatus(kind);
  if (!status.configured) throw new MissingCredentialError(kind, status.missing, status.hint);

  const preMinted = kind === 'oidc-admin' ? CREDENTIALS.adminToken : CREDENTIALS.partnerToken;
  if (preMinted) {
    tokenCache.set(kind, preMinted);
    return preMinted;
  }

  const clientId = kind === 'oidc-admin' ? OIDC.adminClientId : OIDC.partnerClientId;
  const username = kind === 'oidc-admin' ? CREDENTIALS.adminUsername : CREDENTIALS.partnerUsername;
  const password = kind === 'oidc-admin' ? CREDENTIALS.adminPassword : CREDENTIALS.partnerPassword;

  const form = new URLSearchParams({
    grant_type: 'password',
    client_id: clientId,
    username: username!,
    password: password!,
    scope: 'openid',
  });

  let res: Response;
  try {
    res = await fetch(OIDC.tokenEndpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
      signal: AbortSignal.timeout(OIDC.timeoutMs),
    });
  } catch (e) {
    throw new TokenAcquisitionError(
      `Cannot reach the identity provider at ${OIDC.tokenEndpoint}: ` +
        `${(e as Error)?.message ?? e}. Is Keycloak up on that port? ` +
        `Override with GMEPAY_OIDC_ISSUER.`,
    );
  }

  const text = await res.text();
  if (!res.ok) {
    throw new TokenAcquisitionError(
      `Token request to ${OIDC.tokenEndpoint} failed with HTTP ${res.status} for client ` +
        `'${clientId}' / user '${username}': ${text.slice(0, 300)}. ` +
        `'invalid_client' usually means the realm was imported from a stale volume (the seeded ` +
        `public clients were replaced by confidential ones) — recreate the Keycloak volume. ` +
        `'invalid_grant' means the username/password is wrong for realm ${OIDC.issuer}.`,
    );
  }

  let token: string | undefined;
  try {
    token = JSON.parse(text)?.access_token;
  } catch {
    /* fall through to the error below */
  }
  if (!token) {
    throw new TokenAcquisitionError(
      `Token endpoint returned no access_token: ${text.slice(0, 300)}`,
    );
  }
  tokenCache.set(kind, token);
  return token;
}

/**
 * Decode a JWT payload without verifying it. Used only to report what the tester
 * is about to present (claims drive authorization now, so showing them turns a
 * bare 403 into an actionable message).
 */
export function decodeClaims(token: string): Record<string, unknown> | undefined {
  const part = token.split('.')[1];
  if (!part) return undefined;
  try {
    const json = Buffer.from(part.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
    return JSON.parse(json);
  } catch {
    return undefined;
  }
}

/** Normalise the `permissions` claim, which the BFF accepts as an array OR a CSV string. */
export function permissionsOf(claims: Record<string, unknown> | undefined): string[] {
  const raw = claims?.permissions;
  if (Array.isArray(raw)) return raw.map(String);
  if (typeof raw === 'string') return raw.split(',').map((s) => s.trim()).filter(Boolean);
  return [];
}

/** The partner business CODE the token is scoped to (`partner_id`, with fallbacks). */
export function partnerIdOf(claims: Record<string, unknown> | undefined): string | undefined {
  const v = claims?.partner_id ?? claims?.partnerId ?? claims?.tenant_id;
  return v === undefined || v === null ? undefined : String(v);
}

/**
 * Build the headers that present `kind`. Throws MissingCredentialError when the
 * environment does not supply it, so the caller can report BLOCKED with the
 * variable name rather than sending an unauthenticated request and guessing.
 */
export async function credentialHeaders(kind: CredentialKind): Promise<Record<string, string>> {
  switch (kind) {
    case 'none':
      return {};
    case 'internal': {
      const status = credentialStatus(kind);
      if (!status.configured) throw new MissingCredentialError(kind, status.missing, status.hint);
      return { 'X-Gme-Internal': CREDENTIALS.internalToken! };
    }
    case 'oidc-admin':
    case 'oidc-partner':
      return { Authorization: `Bearer ${await bearerToken(kind)}` };
    case 'partner-key': {
      const status = credentialStatus(kind);
      if (!status.configured) throw new MissingCredentialError(kind, status.missing, status.hint);
      return {};
    }
  }
}

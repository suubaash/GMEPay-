/**
 * Minimal browser-side OIDC authorization-code flow with PKCE for Keycloak.
 *
 * Keycloak is the ONLY credential source: the legacy `password=demo` BFF login
 * (`POST /v1/auth/login`) was deleted server-side (gap T0-1), so the admin-ui
 * redirects operators to the realm and runs the standard authorization-code +
 * PKCE dance in the browser. The `admin-ui` client is *public* (no client_secret
 * in a browser bundle), so PKCE is mandatory.
 *
 * Canonical topology (gap T1-2, docker/keycloak/README.md §1):
 *   realm  `gmepay`
 *   client `admin-ui`  ← was hardcoded to `gmepay-admin-ui`, which exists in no
 *                        seed file or chart, so authorize returned
 *                        "Client not found" in every environment
 *   issuer `http://localhost:8097/realms/gmepay` for local docker (8090 is
 *                        scheme-adapter-zeropay's host port)
 * Both are env-overridable: NEXT_PUBLIC_KEYCLOAK_URL / NEXT_PUBLIC_KEYCLOAK_CLIENT_ID.
 *
 * Flow:
 *   1. {@link buildAuthRequest} — generates a fresh PKCE verifier + state,
 *      caches them in sessionStorage, and returns the authorize URL.
 *   2. Browser navigates to Keycloak; user authenticates.
 *   3. Keycloak redirects back to `/auth/callback?code=...&state=...`.
 *   4. The callback page calls {@link exchangeCode} which POSTs to the
 *      Keycloak token endpoint and returns the access/id/refresh tokens.
 *   5. The session is persisted via {@link storeOidcSession}; from this point
 *      the admin-ui uses the access token as a bearer to the BFF, identical
 *      to the previous JWT flow (so api/client.js continues to work).
 *
 * `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true` no longer enables any password path — it
 * only makes AuthGate land on `/login` (which renders the SSO button) instead of
 * navigating the whole window to Keycloak, which is what vitest/jsdom needs.
 */

const DEFAULT_KEYCLOAK_URL = 'http://localhost:8097/realms/gmepay';
const DEFAULT_CLIENT_ID = 'admin-ui';
const PKCE_VERIFIER_KEY = 'gmepay.oidc.pkceVerifier';
const STATE_KEY = 'gmepay.oidc.state';
const RETURN_TO_KEY = 'gmepay.oidc.returnTo';
const CALLBACK_PATH = '/auth/callback';

/**
 * Resolve the Keycloak realm base URL from the build-time env. Same value is
 * burned into the browser bundle by Next at build, hence the
 * `NEXT_PUBLIC_` prefix.
 */
export function keycloakBaseUrl() {
  if (typeof process !== 'undefined' && process.env?.NEXT_PUBLIC_KEYCLOAK_URL) {
    return process.env.NEXT_PUBLIC_KEYCLOAK_URL;
  }
  return DEFAULT_KEYCLOAK_URL;
}

/**
 * OIDC client_id from the build-time env, defaulting to the id the realm seed
 * actually declares (`admin-ui`).
 */
export function keycloakClientId() {
  if (typeof process !== 'undefined' && process.env?.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID) {
    return process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;
  }
  return DEFAULT_CLIENT_ID;
}

/**
 * Whether the MANUAL login page is used instead of an automatic redirect to
 * Keycloak. Returns false unless the env flag is the literal string "true" (so a
 * typo like `NEXT_PUBLIC_ALLOW_DEV_LOGIN=1` keeps the auto-redirect). Vitest sets
 * it true via `vitest.setup.js` so the suite never navigates jsdom away.
 *
 * It is NOT a password bypass: `POST /v1/auth/login` no longer exists on the BFF.
 */
export function isDevLoginAllowed() {
  if (typeof process === 'undefined') return false;
  return process.env?.NEXT_PUBLIC_ALLOW_DEV_LOGIN === 'true';
}

function origin() {
  if (typeof window === 'undefined') return '';
  return window.location.origin;
}

export function callbackUrl() {
  return `${origin()}${CALLBACK_PATH}`;
}

/**
 * Cryptographically random URL-safe string of `byteLen` bytes (base64url-encoded).
 * Falls back to Math.random in jsdom where crypto.getRandomValues may be
 * missing — that's fine because tests don't run the real flow.
 */
function randomB64Url(byteLen) {
  const bytes = new Uint8Array(byteLen);
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < byteLen; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  return base64UrlEncode(bytes);
}

function base64UrlEncode(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  const b64 = typeof btoa === 'function' ? btoa(bin) : Buffer.from(bin, 'binary').toString('base64');
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** SHA-256 of the supplied ASCII verifier, encoded base64url. */
async function pkceChallenge(verifier) {
  const enc = new TextEncoder().encode(verifier);
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const buf = await crypto.subtle.digest('SHA-256', enc);
    return base64UrlEncode(new Uint8Array(buf));
  }
  // jsdom path: tests never reach here because they short-circuit via dev-skip.
  return verifier;
}

/**
 * Build the Keycloak `authorize` URL plus side effects: stashes the PKCE
 * verifier + CSRF state in sessionStorage so {@link exchangeCode} can pair
 * them on return. Caller passes the post-login destination (defaults to `/`).
 */
export async function buildAuthRequest(returnTo = '/') {
  const verifier = randomB64Url(32);
  const state = randomB64Url(16);
  const challenge = await pkceChallenge(verifier);
  if (typeof window !== 'undefined') {
    try {
      window.sessionStorage.setItem(PKCE_VERIFIER_KEY, verifier);
      window.sessionStorage.setItem(STATE_KEY, state);
      window.sessionStorage.setItem(RETURN_TO_KEY, returnTo);
    } catch {
      /* sessionStorage disabled — flow will fail later; surfaced to operator. */
    }
  }
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: keycloakClientId(),
    redirect_uri: callbackUrl(),
    scope: 'openid profile email',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  });
  return `${keycloakBaseUrl()}/protocol/openid-connect/auth?${params.toString()}`;
}

/**
 * Begin the OIDC flow by navigating away. Caller (the login button) does not
 * have anything to do after this — the browser is leaving the page.
 */
export async function startLogin(returnTo = '/') {
  const url = await buildAuthRequest(returnTo);
  if (typeof window !== 'undefined') {
    window.location.assign(url);
  }
  return url;
}

/**
 * Trade the authorization `code` for tokens. Verifies the `state` matches the
 * one we stashed before redirect (CSRF defense). Returns the parsed token
 * response straight from Keycloak: `{ access_token, expires_in, refresh_token,
 * id_token, token_type, scope }`.
 *
 * Throws on state mismatch or non-2xx token response.
 */
export async function exchangeCode({ code, state }) {
  if (typeof window === 'undefined') {
    throw new Error('exchangeCode must run in the browser');
  }
  const expected = window.sessionStorage.getItem(STATE_KEY);
  const verifier = window.sessionStorage.getItem(PKCE_VERIFIER_KEY);
  if (!expected || expected !== state) {
    throw new Error('OIDC state mismatch — refusing token exchange');
  }
  if (!verifier) {
    throw new Error('OIDC PKCE verifier missing — start the flow again');
  }
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: keycloakClientId(),
    code,
    redirect_uri: callbackUrl(),
    code_verifier: verifier,
  });
  const res = await fetch(`${keycloakBaseUrl()}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Token exchange failed (${res.status}): ${text || res.statusText}`);
  }
  // One-time-use: clear PKCE state right after a successful exchange.
  try {
    window.sessionStorage.removeItem(PKCE_VERIFIER_KEY);
    window.sessionStorage.removeItem(STATE_KEY);
  } catch {
    /* ignore */
  }
  return res.json();
}

/**
 * Silent token refresh (refresh_token grant, public client → no secret). Returns
 * the new token response, or throws so the caller can start a full login. This
 * replaces the deleted BFF `POST /v1/auth/refresh`.
 *
 * @param {string} refreshToken
 * @returns {Promise<object>}
 */
export async function refreshTokens(refreshToken) {
  if (!refreshToken) {
    throw new Error('No refresh token provided');
  }
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: keycloakClientId(),
    refresh_token: refreshToken,
  });
  const res = await fetch(`${keycloakBaseUrl()}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Token refresh failed (${res.status}): ${text || res.statusText}`);
  }
  return res.json();
}

/** Pop the cached returnTo (set at {@link startLogin}); defaults to `/`. */
export function consumeReturnTo() {
  if (typeof window === 'undefined') return '/';
  let v = '/';
  try {
    v = window.sessionStorage.getItem(RETURN_TO_KEY) || '/';
    window.sessionStorage.removeItem(RETURN_TO_KEY);
  } catch {
    /* ignore */
  }
  return v;
}

/**
 * Decode a JWT payload without verifying signature. We only use this to read
 * `preferred_username` and `exp` for UI display + expiry handling; the BFF
 * verifies the signature on every API call as a resource server.
 */
export function decodeJwtPayload(jwt) {
  if (!jwt || typeof jwt !== 'string') return null;
  const parts = jwt.split('.');
  if (parts.length !== 3) return null;
  try {
    const padded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const pad = padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
    const json =
      typeof atob === 'function'
        ? atob(padded + pad)
        : Buffer.from(padded + pad, 'base64').toString('utf-8');
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/**
 * Build the Keycloak end-session URL. Calling this clears the SSO session on
 * the IdP side so the user truly has to re-authenticate, not just lose their
 * local token.
 */
export function logoutUrl(idToken, postLogoutRedirect) {
  const params = new URLSearchParams({
    post_logout_redirect_uri: postLogoutRedirect || `${origin()}/login`,
    client_id: keycloakClientId(),
  });
  if (idToken) params.set('id_token_hint', idToken);
  return `${keycloakBaseUrl()}/protocol/openid-connect/logout?${params.toString()}`;
}

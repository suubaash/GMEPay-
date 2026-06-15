'use client';

/**
 * Browser-side OIDC authorization-code + PKCE flow for the Partner Portal.
 *
 * The partner-portal-ui may be reached by partners who use their own IdP
 * federated into Keycloak (realm `gmepay-partners`, client `gmepay-partner-ui`).
 * The flow is identical to the admin-ui OIDC module but with different
 * realm/client defaults.
 *
 * Flow:
 *   1. {@link buildAuthRequest} — generates PKCE verifier + state, caches in
 *      sessionStorage, returns the authorize URL.
 *   2. Browser navigates to Keycloak; partner authenticates.
 *   3. Keycloak redirects back to `/auth/callback?code=...&state=...`.
 *   4. Callback page calls {@link exchangeCode} which POSTs to the token
 *      endpoint and returns tokens.
 *   5. Caller persists via {@link storeOidcSession}; api/client.js carries the
 *      access_token as `Authorization: Bearer` on every BFF request.
 *
 * Config env vars (NEXT_PUBLIC_ = burned in at build):
 *   NEXT_PUBLIC_KEYCLOAK_URL       Realm base URL (defaults to localhost:8090/realms/gmepay-partners)
 *   NEXT_PUBLIC_KEYCLOAK_CLIENT_ID Client id (defaults to gmepay-partner-ui)
 *   NEXT_PUBLIC_ALLOW_DEV_LOGIN    "true" => show password form fallback
 *
 * Dev-skip escape hatch:
 *   When `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true`, the login page keeps the Phase-1
 *   password form visible so vitest + local-no-Keycloak iteration still works.
 */

const DEFAULT_KEYCLOAK_URL = 'http://localhost:8090/realms/gmepay-partners';
const DEFAULT_CLIENT_ID = 'gmepay-partner-ui';

const PKCE_VERIFIER_KEY = 'gmepay.portal.oidc.pkceVerifier';
const STATE_KEY = 'gmepay.portal.oidc.state';
const RETURN_TO_KEY = 'gmepay.portal.oidc.returnTo';
const CALLBACK_PATH = '/auth/callback';

/**
 * Keycloak realm base URL from build-time env.
 * @returns {string}
 */
export function keycloakBaseUrl() {
  if (typeof process !== 'undefined' && process.env?.NEXT_PUBLIC_KEYCLOAK_URL) {
    return process.env.NEXT_PUBLIC_KEYCLOAK_URL;
  }
  return DEFAULT_KEYCLOAK_URL;
}

/**
 * OIDC client_id from build-time env.
 * @returns {string}
 */
export function keycloakClientId() {
  if (typeof process !== 'undefined' && process.env?.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID) {
    return process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;
  }
  return DEFAULT_CLIENT_ID;
}

/**
 * Whether the dev escape hatch is active. Returns false unless the env flag
 * is the literal string "true" (typos keep the real OIDC flow).
 * @returns {boolean}
 */
export function isDevLoginAllowed() {
  if (typeof process === 'undefined') return false;
  return process.env?.NEXT_PUBLIC_ALLOW_DEV_LOGIN === 'true';
}

function origin() {
  if (typeof window === 'undefined') return '';
  return window.location.origin;
}

/**
 * Absolute URL that Keycloak must redirect to after auth.
 * @returns {string}
 */
export function callbackUrl() {
  return `${origin()}${CALLBACK_PATH}`;
}

/**
 * Cryptographically random URL-safe string of `byteLen` bytes (base64url).
 * Falls back to Math.random in jsdom where crypto.getRandomValues may not
 * exist — fine because tests short-circuit via dev-skip.
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
  const b64 =
    typeof btoa === 'function'
      ? btoa(bin)
      : Buffer.from(bin, 'binary').toString('base64');
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * SHA-256 of the PKCE verifier, base64url-encoded.
 * jsdom path: crypto.subtle may not exist — tests never exercise this because
 * they use the dev-skip path.
 *
 * @param {string} verifier
 * @returns {Promise<string>}
 */
export async function pkceChallenge(verifier) {
  const enc = new TextEncoder().encode(verifier);
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const buf = await crypto.subtle.digest('SHA-256', enc);
    return base64UrlEncode(new Uint8Array(buf));
  }
  // Fallback for environments without subtle crypto (e.g. jsdom).
  return verifier;
}

/**
 * Build the Keycloak `authorize` URL and stash PKCE verifier + state in
 * sessionStorage so {@link exchangeCode} can validate them on return.
 *
 * @param {string} [returnTo='/'] Post-login destination path.
 * @returns {Promise<string>} Full authorize URL.
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
      /* sessionStorage disabled — will surface an error later. */
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
 * Kick off the OIDC flow. The browser navigates away; there is nothing to do
 * after calling this — the page is leaving.
 *
 * @param {string} [returnTo='/'] Path to land on after successful sign-in.
 * @returns {Promise<string>} Resolves to the redirect URL (useful in tests).
 */
export async function startLogin(returnTo = '/') {
  const url = await buildAuthRequest(returnTo);
  if (typeof window !== 'undefined') {
    window.location.assign(url);
  }
  return url;
}

/**
 * Trade the authorization `code` for tokens. Verifies the `state` parameter
 * (CSRF defense), consumes the PKCE verifier, and POSTs to the Keycloak
 * token endpoint.
 *
 * Returns the parsed token response:
 *   `{ access_token, expires_in, refresh_token, id_token, token_type, scope }`
 *
 * Throws on state mismatch or non-2xx token response.
 *
 * @param {{ code: string, state: string }} params
 * @returns {Promise<object>}
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

  const res = await fetch(
    `${keycloakBaseUrl()}/protocol/openid-connect/token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    }
  );

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(
      `Token exchange failed (${res.status}): ${text || res.statusText}`
    );
  }

  // Single-use: clear PKCE state after successful exchange.
  try {
    window.sessionStorage.removeItem(PKCE_VERIFIER_KEY);
    window.sessionStorage.removeItem(STATE_KEY);
  } catch {
    /* ignore */
  }

  return res.json();
}

/**
 * Attempt a silent token refresh using the stored refresh_token.
 * Returns the new token response, or throws on failure (caller should
 * then initiate a full login).
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

  const res = await fetch(
    `${keycloakBaseUrl()}/protocol/openid-connect/token`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    }
  );

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(
      `Token refresh failed (${res.status}): ${text || res.statusText}`
    );
  }

  return res.json();
}

/**
 * Pop the cached returnTo path (set at {@link startLogin}); defaults to `/`.
 * @returns {string}
 */
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
 * Decode a JWT payload without verifying the signature.
 * Used only to read `preferred_username` / `email` for UI display and expiry
 * handling; the BFF resource-server verifies the signature on every API call.
 *
 * @param {string|null} jwt
 * @returns {object|null}
 */
export function decodeJwtPayload(jwt) {
  if (!jwt || typeof jwt !== 'string') return null;
  const parts = jwt.split('.');
  if (parts.length !== 3) return null;
  try {
    const padded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const pad =
      padded.length % 4 === 0 ? '' : '='.repeat(4 - (padded.length % 4));
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
 * Build the Keycloak end-session URL so the SSO cookie is cleared on the IdP.
 *
 * @param {string|null} idToken   id_token from the OIDC session (id_token_hint).
 * @param {string} [postLogoutRedirect] Defaults to `<origin>/login`.
 * @returns {string}
 */
export function logoutUrl(idToken, postLogoutRedirect) {
  const params = new URLSearchParams({
    post_logout_redirect_uri: postLogoutRedirect || `${origin()}/login`,
    client_id: keycloakClientId(),
  });
  if (idToken) params.set('id_token_hint', idToken);
  return `${keycloakBaseUrl()}/protocol/openid-connect/logout?${params.toString()}`;
}

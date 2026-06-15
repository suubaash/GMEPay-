'use client';

/**
 * Partner Portal auth helpers.
 *
 * Phase 1: token + partnerId stored in localStorage, sent as
 *   Authorization: Bearer <token>
 *   X-Partner-Id: <partnerId>
 *
 * Phase 2 (OIDC): Keycloak authorization-code + PKCE. The access_token from
 *   Keycloak becomes the bearer; api-gateway acts as the OAuth2 resource
 *   server. The dev-skip password form is retained behind
 *   NEXT_PUBLIC_ALLOW_DEV_LOGIN=true for local iteration without Keycloak.
 *
 * BFF wire contract (see docs/INTER_SERVICE_CONTRACTS.md):
 *   POST /v1/auth/login
 *     body  : { username, password }
 *     reply : { token, expiresAt, role }
 *
 * The BFF does NOT return a partnerId — the Portal UI treats the form's
 * `partnerId` field as the partner identity for `X-Partner-Id` and stores it
 * locally alongside the token.
 *
 * @typedef {object} LoginRequest
 * @property {string} partnerId  - Used as the X-Partner-Id header value (UI-local).
 * @property {string} password   - Demo password is "demo" in Phase 1.
 *
 * @typedef {object} LoginResponse
 * @property {string} token       - Mock JWT-shaped string from BFF.
 * @property {string} partnerId   - UI-local: mirrors the partnerId from the form.
 * @property {string} [expiresAt] - ISO-8601 instant when the token expires.
 * @property {string} [role]      - Role claim from the BFF (e.g. "ADMIN").
 */
import { decodeJwtPayload } from './oidc';

export const TOKEN_KEY = 'gmepay.partnerToken';
export const PARTNER_ID_KEY = 'gmepay.partnerId';
export const EXPIRES_AT_KEY = 'gmepay.partnerTokenExpiresAt';
export const ID_TOKEN_KEY = 'gmepay.partnerIdToken';
export const REFRESH_TOKEN_KEY = 'gmepay.partnerRefreshToken';

function safeLocalStorage() {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** @returns {string | null} Persisted bearer (access) token, or null if not signed in / SSR. */
export function getToken() {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(TOKEN_KEY) : null;
}

/** @returns {string | null} Persisted OIDC id_token, or null if absent / SSR. */
export function getIdToken() {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(ID_TOKEN_KEY) : null;
}

/** @returns {string | null} Persisted OIDC refresh_token, or null if absent / SSR. */
export function getRefreshToken() {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(REFRESH_TOKEN_KEY) : null;
}

/** @returns {string | null} Persisted partner id, or null if not signed in / SSR. */
export function getPartnerId() {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(PARTNER_ID_KEY) : null;
}

/**
 * Whether a session is present and not expired.
 *
 * For OIDC sessions we check the cached expiry epoch (set by
 * {@link storeOidcSession}). A token past its `exp` is treated as logged-out
 * so AuthGate can kick the partner back to Keycloak for a fresh login.
 * Signature verification is the BFF's job.
 *
 * For Phase-1 password sessions (no EXPIRES_AT_KEY) we fall back to the
 * original "token + partnerId present" check.
 *
 * @returns {boolean}
 */
export function isAuthenticated() {
  const token = getToken();
  if (!token) return false;
  const ls = safeLocalStorage();
  if (!ls) return true;
  const epoch = ls.getItem(EXPIRES_AT_KEY);
  if (!epoch) {
    // Phase-1 path: require partnerId too.
    return Boolean(ls.getItem(PARTNER_ID_KEY));
  }
  const ms = Number(epoch);
  if (!Number.isFinite(ms)) return true; // ISO string from dev-skip: treat as fresh
  return Date.now() < ms;
}

function setToken(token) {
  const ls = safeLocalStorage();
  if (ls) ls.setItem(TOKEN_KEY, token);
}

function setPartnerId(id) {
  const ls = safeLocalStorage();
  if (ls) ls.setItem(PARTNER_ID_KEY, id);
}

/**
 * Persist an OIDC token response (the JSON returned by Keycloak's /token
 * endpoint). The access_token becomes the bearer used by api/client.js.
 *
 * EXPIRES_AT_KEY is stored as ms-since-epoch for a numeric compare in
 * {@link isAuthenticated}. The Phase-1 dev-skip path stores an ISO string —
 * {@link isAuthenticated} handles both.
 *
 * @param {object} tokenResponse  Keycloak token response
 */
export function storeOidcSession(tokenResponse) {
  if (typeof window === 'undefined') return;
  if (!tokenResponse?.access_token) return;
  const ls = safeLocalStorage();
  if (!ls) return;
  try {
    ls.setItem(TOKEN_KEY, tokenResponse.access_token);
    if (tokenResponse.id_token) {
      ls.setItem(ID_TOKEN_KEY, tokenResponse.id_token);
    }
    if (tokenResponse.refresh_token) {
      ls.setItem(REFRESH_TOKEN_KEY, tokenResponse.refresh_token);
    }
    if (Number.isFinite(tokenResponse.expires_in)) {
      const epochMs = Date.now() + tokenResponse.expires_in * 1000;
      ls.setItem(EXPIRES_AT_KEY, String(epochMs));
    }
    // Derive partnerId from the id_token claims when available, so that the
    // X-Partner-Id header keeps working in the OIDC path.
    const claims = decodeJwtPayload(tokenResponse.id_token);
    if (claims) {
      const pid =
        claims.partner_id ??
        claims.preferred_username ??
        claims.email ??
        null;
      if (pid) ls.setItem(PARTNER_ID_KEY, pid);
    }
  } catch {
    /* quota / disabled */
  }
}

/**
 * Clear all auth-related localStorage keys. Called by logout paths.
 */
export function clearAuth() {
  const ls = safeLocalStorage();
  if (!ls) return;
  try {
    ls.removeItem(TOKEN_KEY);
    ls.removeItem(PARTNER_ID_KEY);
    ls.removeItem(EXPIRES_AT_KEY);
    ls.removeItem(ID_TOKEN_KEY);
    ls.removeItem(REFRESH_TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

/**
 * Sign in. POSTs `{ username, password }` to the BFF and, on success, stores
 * the returned token alongside the form-supplied partnerId. Throws on non-2xx
 * with a `.status` field so the login form can distinguish 401 from 5xx.
 *
 * Note: the BFF reply does NOT include a partnerId (per LoginResponse); we
 * mirror the form value so `X-Partner-Id` is always available client-side.
 *
 * @param {LoginRequest} req
 * @returns {Promise<LoginResponse>}
 */
export async function login(req) {
  const base = process.env.NEXT_PUBLIC_BFF_BASE_URL || '';
  const url = base ? `${base}/v1/auth/login` : `/api/v1/auth/login`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    // The BFF reads { username, password } — map partnerId -> username on the wire.
    body: JSON.stringify({ username: req.partnerId, password: req.password }),
    cache: 'no-store'
  });

  if (!res.ok) {
    // Read the response body as text FIRST — once a Response body stream has
    // been consumed (even by a failing res.json()), subsequent reads throw
    // `TypeError: Body is unusable`. Parse JSON from the text if possible.
    let body;
    try {
      const raw = await res.text();
      try {
        body = JSON.parse(raw);
      } catch {
        body = raw;
      }
    } catch {
      body = undefined;
    }
    const err = new Error(
      res.status === 401 || res.status === 403
        ? 'Invalid partner id or password'
        : `Login failed (HTTP ${res.status})`
    );
    err.status = res.status;
    err.body = body;
    throw err;
  }

  const data = await res.json();
  if (!data || !data.token) {
    throw new Error('Login response missing token');
  }
  setToken(data.token);
  setPartnerId(req.partnerId);
  return {
    token: data.token,
    partnerId: req.partnerId,
    expiresAt: data.expiresAt,
    role: data.role
  };
}

/**
 * End the session. Clears local auth and, when an OIDC id_token is present,
 * redirects the browser to Keycloak's end-session endpoint so the SSO cookie
 * is cleared too. Falls back to the local /login route otherwise.
 */
export function logout() {
  const idToken = getIdToken();
  clearAuth();
  if (typeof window === 'undefined') return;
  if (idToken) {
    // Import lazily to avoid a circular dep at module evaluation time
    // (oidc.js imports nothing from auth.js, so the cycle is one-way).
    import('./oidc').then(({ logoutUrl }) => {
      window.location.assign(logoutUrl(idToken));
    }).catch(() => {
      window.location.assign('/login');
    });
  } else {
    window.location.assign('/login');
  }
}

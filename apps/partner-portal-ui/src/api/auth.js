'use client';

/**
 * Partner Portal auth helpers.
 *
 * Phase 1: token + partnerId stored in localStorage, sent as
 *   Authorization: Bearer <token>
 *   X-Partner-Id: <partnerId>
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
 * Phase 2 will replace this with OAuth2 / partner SSO behind httpOnly cookies;
 * the hook surface (login/logout/getToken) is stable across that change.
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

export const TOKEN_KEY = 'gmepay.partnerToken';
export const PARTNER_ID_KEY = 'gmepay.partnerId';

function safeLocalStorage() {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** @returns {string | null} Persisted bearer token, or null if not signed in / SSR. */
export function getToken() {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(TOKEN_KEY) : null;
}

/** @returns {string | null} Persisted partner id, or null if not signed in / SSR. */
export function getPartnerId() {
  const ls = safeLocalStorage();
  return ls ? ls.getItem(PARTNER_ID_KEY) : null;
}

/** @returns {boolean} */
export function isAuthenticated() {
  return Boolean(getToken() && getPartnerId());
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

/** Clear local credentials. Does not call the server (Phase 1). */
export function logout() {
  const ls = safeLocalStorage();
  if (!ls) return;
  ls.removeItem(TOKEN_KEY);
  ls.removeItem(PARTNER_ID_KEY);
}

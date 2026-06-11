/**
 * Auth boilerplate for the admin-ui.
 *
 * As of Slice 1 (PARTNER_SETUP_PLAN.md), authentication is delegated to
 * Keycloak via OIDC authorization-code + PKCE (see ./oidc.js). The legacy
 * username/password BFF login is retired in production but remains available
 * behind `NEXT_PUBLIC_ALLOW_DEV_LOGIN=true` so vitest + local-no-Keycloak
 * iteration still work.
 *
 * The session model the rest of the app sees is unchanged: an opaque bearer
 * token in localStorage under TOKEN_KEY, read by {@link client.js} and
 * injected as `Authorization: Bearer <token>` on every BFF request. What
 * changes underneath is *how* the token gets there:
 *   - OIDC path: the callback page exchanges the auth code for tokens and
 *     calls {@link storeOidcSession}. The access_token from Keycloak becomes
 *     the bearer; api-gateway / BFF act as OAuth2 resource servers.
 *   - Dev-skip path: the legacy `loginThunk` is allowed and behaves as before.
 *
 * The token is intentionally still in localStorage for Slice 1 — ADR-011
 * notes that phase-D will migrate to httpOnly cookies issued by the BFF once
 * the BFF acquires a session endpoint.
 */
import { adminApi } from './client';
import { decodeJwtPayload, logoutUrl as oidcLogoutUrl } from './oidc';

export const TOKEN_KEY = 'gmepay.adminToken';
export const USER_KEY = 'gmepay.adminUser';
export const EXPIRES_AT_KEY = 'gmepay.adminTokenExpiresAt';
export const ROLE_KEY = 'gmepay.adminRole';
export const ID_TOKEN_KEY = 'gmepay.adminIdToken';
export const REFRESH_TOKEN_KEY = 'gmepay.adminRefreshToken';

/** Read the stored bearer (access) token, or null on server / logged out. */
export function getToken() {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Read the cached OIDC ID token (used to drive logout + parse claims). */
export function getIdToken() {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(ID_TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Whether a session is present. For OIDC we additionally check the cached
 * expiry epoch (set when the token came in); a token that has elapsed its
 * `exp` claim is treated as logged-out so AuthGate boots the user back to
 * Keycloak for a fresh login. Signature verification is still the BFF's job.
 */
export function isAuthenticated() {
  const token = getToken();
  if (!token) return false;
  if (typeof window === 'undefined') return true;
  try {
    const epoch = window.localStorage.getItem(EXPIRES_AT_KEY);
    if (!epoch) return true;
    // EXPIRES_AT_KEY is stored as ms-since-epoch for OIDC; legacy dev-skip
    // path stores an ISO string which Number(...) -> NaN, treat as fresh.
    const ms = Number(epoch);
    if (!Number.isFinite(ms)) return true;
    return Date.now() < ms;
  } catch {
    return true;
  }
}

/** Persist the access (bearer) token; safe-no-op on the server. */
export function setToken(token) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* quota / disabled — ignore */
  }
}

/** Clear every auth-related localStorage key. */
export function clearAuth() {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.removeItem(TOKEN_KEY);
    window.localStorage.removeItem(USER_KEY);
    window.localStorage.removeItem(EXPIRES_AT_KEY);
    window.localStorage.removeItem(ROLE_KEY);
    window.localStorage.removeItem(ID_TOKEN_KEY);
    window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

/**
 * Username to render in the AppShell header. Reads first from the cached
 * USER_KEY (set by both OIDC and dev-skip paths) and falls back to parsing
 * the ID token's `preferred_username` claim — that way a hard-refresh after
 * an OIDC login still shows the right name even if USER_KEY was evicted.
 */
export function getUsername() {
  if (typeof window === 'undefined') return null;
  try {
    const cached = window.localStorage.getItem(USER_KEY);
    if (cached) return cached;
  } catch {
    /* ignore */
  }
  const claims = decodeJwtPayload(getIdToken());
  return claims?.preferred_username ?? claims?.name ?? claims?.email ?? null;
}

/**
 * Persist an OIDC token response (the JSON returned by Keycloak's
 * /token endpoint). Stores the access token as the bearer used by
 * api/client.js, plus the id_token (for username + logout) and refresh_token
 * (reserved for a future silent-refresh hook).
 *
 * EXPIRES_AT_KEY is set to **ms-since-epoch** when present, so
 * {@link isAuthenticated} can do a cheap numeric compare. The legacy dev-skip
 * code path keeps storing an ISO string there for backward compat.
 */
export function storeOidcSession(tokenResponse) {
  if (typeof window === 'undefined') return;
  if (!tokenResponse?.access_token) return;
  try {
    window.localStorage.setItem(TOKEN_KEY, tokenResponse.access_token);
    if (tokenResponse.id_token) {
      window.localStorage.setItem(ID_TOKEN_KEY, tokenResponse.id_token);
    }
    if (tokenResponse.refresh_token) {
      window.localStorage.setItem(REFRESH_TOKEN_KEY, tokenResponse.refresh_token);
    }
    if (Number.isFinite(tokenResponse.expires_in)) {
      const epochMs = Date.now() + tokenResponse.expires_in * 1000;
      window.localStorage.setItem(EXPIRES_AT_KEY, String(epochMs));
    }
    const claims = decodeJwtPayload(tokenResponse.id_token);
    if (claims?.preferred_username) {
      window.localStorage.setItem(USER_KEY, claims.preferred_username);
    } else if (claims?.email) {
      window.localStorage.setItem(USER_KEY, claims.email);
    }
    // Keycloak realm_access.roles contains the realm role names; surface the
    // first one as the canonical "role" for parity with the old BFF shape.
    const role = claims?.realm_access?.roles?.[0];
    if (role) {
      window.localStorage.setItem(ROLE_KEY, role);
    }
  } catch {
    /* ignore */
  }
}

/**
 * Legacy username/password login against the BFF. Retained for the dev-skip
 * escape hatch (NEXT_PUBLIC_ALLOW_DEV_LOGIN=true) — DO NOT call from the
 * production login path.
 *
 * BFF returns `{ token, expiresAt, role }` — username comes from the form
 * input (BFF does NOT echo it back).
 */
export async function login(req) {
  const res = await adminApi.login(req);
  setToken(res.token);
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(USER_KEY, req.username);
      if (res.expiresAt) {
        window.localStorage.setItem(EXPIRES_AT_KEY, String(res.expiresAt));
      }
      if (res.role) {
        window.localStorage.setItem(ROLE_KEY, res.role);
      }
    } catch {
      /* ignore */
    }
  }
  return res;
}

/**
 * End the session. Clears local auth and redirects the browser to Keycloak's
 * end-session endpoint when an OIDC session is present (so the SSO cookie is
 * cleared on the IdP too). Falls back to the local /login route otherwise.
 */
export function logout() {
  const idToken = getIdToken();
  clearAuth();
  if (typeof window === 'undefined') return;
  if (idToken) {
    window.location.assign(oidcLogoutUrl(idToken));
  } else {
    window.location.assign('/login');
  }
}

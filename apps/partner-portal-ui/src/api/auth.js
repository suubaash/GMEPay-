'use client';

/**
 * Partner Portal auth helpers — Keycloak OIDC only.
 *
 * The single credential source is Keycloak (authorization-code + PKCE, see
 * ./oidc.js). `ops-partner-bff` is an OAuth2 resource server: it verifies the
 * access token's signature/issuer/expiry and takes BOTH authorization and the
 * partner scope from the token's own claims (`security/TokenClaims.java`).
 *
 * What was removed (gap register T0-1 / T0-4 / T1-2):
 *   - `login()` POSTing `{username, password}` to `POST /v1/auth/login`. That
 *     endpoint and its DTOs were DELETED from the BFF; it now 404s, and the
 *     unsigned `role:ADMIN` token it used to mint is rejected by the resource
 *     server anyway.
 *   - the `X-Partner-Id` header as an identity. The BFF reads it nowhere; tenant
 *     scope comes from the `partner_id` claim. It is kept here as a *local*
 *     value only, because `/v1/portal/{partnerId}/**` needs it in the PATH.
 *
 * The stored partner id therefore MUST come from the token, and must equal a
 * partner code the platform actually knows (`GMEREMIT` / `SENDMN` from
 * config-registry's PartnerSeeder). Anything else is a guaranteed 403 from
 * `OpsRbacGuard.requirePartnerScope`.
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
 * We check the cached expiry epoch (set by {@link storeOidcSession}). A token
 * past its `exp` is treated as logged-out so AuthGate can kick the partner back
 * to Keycloak for a fresh login. Signature verification is the BFF's job.
 *
 * A token with no cached expiry (hand-injected in a test, or written by an older
 * build) still requires a partner id to count as a session — without one no
 * `/v1/portal/{partnerId}/**` call can be addressed at all.
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
    // No cached expiry: require a partner id before calling this a session.
    return Boolean(ls.getItem(PARTNER_ID_KEY));
  }
  const ms = Number(epoch);
  if (!Number.isFinite(ms)) return true; // ISO string from dev-skip: treat as fresh
  return Date.now() < ms;
}

/**
 * The partner code this token is scoped to, taken from the `partner_id` claim —
 * the SAME claim `TokenClaims.partnerIdOf` reads server-side, checked on the
 * access token first and the id_token second (the realm maps it into both).
 *
 * Returns null when the claim is absent. It deliberately does NOT fall back to
 * `preferred_username` / `email`: the value is used as the `{partnerId}` path
 * segment, so a username would produce a guaranteed 403 ("token is scoped to a
 * different partner") that looks like a data bug instead of a missing mapper.
 *
 * @param {object} tokenResponse Keycloak token response
 * @returns {string | null}
 */
export function partnerIdFromTokens(tokenResponse) {
  const fromAccess = decodeJwtPayload(tokenResponse?.access_token)?.partner_id;
  if (typeof fromAccess === 'string' && fromAccess.trim()) return fromAccess.trim();
  const fromId = decodeJwtPayload(tokenResponse?.id_token)?.partner_id;
  if (typeof fromId === 'string' && fromId.trim()) return fromId.trim();
  return null;
}

/**
 * True when we hold a session but the token carries no partner scope — i.e. the
 * Keycloak user has no `partner_id` attribute (or the `gmepay-partner-id`
 * protocol mapper is missing from the client). Every portal page would 403/404,
 * so AuthGate surfaces this explicitly instead of rendering broken pages.
 *
 * @returns {boolean}
 */
export function isPartnerScopeMissing() {
  return Boolean(getToken()) && !getPartnerId();
}

/**
 * Persist an OIDC token response (the JSON returned by Keycloak's /token
 * endpoint). The access_token becomes the bearer used by api/client.js.
 *
 * EXPIRES_AT_KEY is stored as ms-since-epoch for a numeric compare in
 * {@link isAuthenticated}.
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
    // Partner scope comes from the token's partner_id claim ONLY (see
    // partnerIdFromTokens). A stale value from a previous session must not
    // survive a login as a different partner, hence the explicit remove.
    const pid = partnerIdFromTokens(tokenResponse);
    if (pid) {
      ls.setItem(PARTNER_ID_KEY, pid);
    } else {
      ls.removeItem(PARTNER_ID_KEY);
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
 * Refresh the access token with the stored refresh_token and persist the result.
 * Returns the new access token, or null when there is nothing to refresh / the
 * refresh was rejected (caller should then start a fresh Keycloak login).
 *
 * @returns {Promise<string | null>}
 */
export async function refreshSession() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;
  try {
    const { refreshTokens } = await import('./oidc');
    const tokenResponse = await refreshTokens(refreshToken);
    if (!tokenResponse?.access_token) return null;
    storeOidcSession(tokenResponse);
    return tokenResponse.access_token;
  } catch {
    return null;
  }
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

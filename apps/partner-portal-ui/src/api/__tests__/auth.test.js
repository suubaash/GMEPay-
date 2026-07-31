import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  TOKEN_KEY,
  PARTNER_ID_KEY,
  EXPIRES_AT_KEY,
  REFRESH_TOKEN_KEY,
  getToken,
  getPartnerId,
  isAuthenticated,
  isPartnerScopeMissing,
  partnerIdFromTokens,
  storeOidcSession,
  logout
} from '../auth';

/**
 * Contract lock (T0-4 / T1-2): the partner scope stored client-side is the
 * token's `partner_id` claim — the SAME claim `TokenClaims.partnerIdOf` reads
 * server-side to authorize `/v1/portal/{partnerId}/**`. There is no password
 * login to test any more: `POST /v1/auth/login` was deleted from the BFF.
 */

/** Build an unsigned JWT with the given payload (signature is never checked here). */
function jwt(payload) {
  const enc = (o) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${enc({ alg: 'RS256' })}.${enc(payload)}.sig`;
}

describe('api/auth', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('storage helpers', () => {
    it('returns null when no token is persisted', () => {
      expect(getToken()).toBeNull();
      expect(getPartnerId()).toBeNull();
      expect(isAuthenticated()).toBe(false);
    });

    it('treats a token with no cached expiry as a session only with a partner id', () => {
      window.localStorage.setItem(TOKEN_KEY, 'tkn');
      expect(isAuthenticated()).toBe(false);
      window.localStorage.setItem(PARTNER_ID_KEY, 'GMEREMIT');
      expect(isAuthenticated()).toBe(true);
    });

    it('treats an expired OIDC session as logged out', () => {
      window.localStorage.setItem(TOKEN_KEY, 'tkn');
      window.localStorage.setItem(PARTNER_ID_KEY, 'GMEREMIT');
      window.localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() - 1000));
      expect(isAuthenticated()).toBe(false);
    });
  });

  describe('partnerIdFromTokens()', () => {
    it('prefers the access token partner_id claim', () => {
      const pid = partnerIdFromTokens({
        access_token: jwt({ partner_id: 'GMEREMIT' }),
        id_token: jwt({ partner_id: 'SENDMN' })
      });
      expect(pid).toBe('GMEREMIT');
    });

    it('falls back to the id_token partner_id claim', () => {
      const pid = partnerIdFromTokens({
        access_token: jwt({ sub: 'u1' }),
        id_token: jwt({ partner_id: 'SENDMN' })
      });
      expect(pid).toBe('SENDMN');
    });

    it('does NOT fall back to preferred_username / email', () => {
      // Falling back would put a username in the {partnerId} path segment, which
      // the BFF answers with 403 "token is scoped to a different partner" — a
      // misleading failure that hides the real cause (missing claim mapper).
      const pid = partnerIdFromTokens({
        access_token: jwt({ preferred_username: 'partner-demo' }),
        id_token: jwt({ email: 'partner-demo@gmepay.local' })
      });
      expect(pid).toBeNull();
    });
  });

  describe('storeOidcSession()', () => {
    it('persists the access token, refresh token, expiry and partner scope', () => {
      storeOidcSession({
        access_token: jwt({ partner_id: 'GMEREMIT' }),
        id_token: jwt({ preferred_username: 'partner-demo' }),
        refresh_token: 'refresh-abc',
        expires_in: 900
      });
      expect(window.localStorage.getItem(TOKEN_KEY)).toContain('.');
      expect(window.localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('refresh-abc');
      expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBe('GMEREMIT');
      expect(Number(window.localStorage.getItem(EXPIRES_AT_KEY))).toBeGreaterThan(Date.now());
      expect(isPartnerScopeMissing()).toBe(false);
    });

    it('clears a stale partner id when the new token carries no partner_id', () => {
      window.localStorage.setItem(PARTNER_ID_KEY, 'SENDMN');
      storeOidcSession({
        access_token: jwt({ sub: 'hub-operator' }),
        id_token: jwt({ preferred_username: 'admin' }),
        expires_in: 900
      });
      expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBeNull();
      expect(isPartnerScopeMissing()).toBe(true);
    });
  });

  describe('logout()', () => {
    it('clears every persisted credential', () => {
      window.localStorage.setItem(TOKEN_KEY, 'tkn');
      window.localStorage.setItem(PARTNER_ID_KEY, 'GMEREMIT');
      window.localStorage.setItem(REFRESH_TOKEN_KEY, 'refresh-abc');
      logout();
      expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();
      expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBeNull();
      expect(window.localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    });
  });

  it('no longer exports a password login', async () => {
    const mod = await import('../auth');
    expect(mod.login).toBeUndefined();
  });
});

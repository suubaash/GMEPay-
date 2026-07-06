import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  TOKEN_KEY,
  PARTNER_ID_KEY,
  getToken,
  getPartnerId,
  isAuthenticated,
  login,
  logout
} from '../auth';

/**
 * Contract lock (real-auth slice): the BFF proxies auth-identity verbatim, so
 * the reply is `{ token, expiresAt, tokenType, username, roles }` — a REAL
 * 3-part HS256 JWT, epoch-second expiry, and a `roles` array; no partnerId.
 * `login()` mirrors the request's `partnerId` onto the persisted
 * LoginResponse and into localStorage so the UI has a stable identity for
 * the X-Partner-Id header, and surfaces `roles[0]` as the legacy `role` field.
 */
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

    it('isAuthenticated requires both token and partnerId', () => {
      window.localStorage.setItem(TOKEN_KEY, 'tkn');
      expect(isAuthenticated()).toBe(false);
      window.localStorage.setItem(PARTNER_ID_KEY, 'GMEREMIT');
      expect(isAuthenticated()).toBe(true);
    });
  });

  describe('login()', () => {
    it('persists token (from BFF proxy) + partnerId (from form) on success', async () => {
      // Real proxy reply shape: 3-part HS256 JWT + epoch expiry + roles array.
      const realJwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJHTUVSRU1JVCJ9.c2ln';
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(
          JSON.stringify({
            token: realJwt,
            expiresAt: 1750000000,
            tokenType: 'Bearer',
            username: 'GMEREMIT',
            roles: ['HUB_ADMIN']
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        )
      );

      const out = await login({ partnerId: 'GMEREMIT', password: 'gmepay-dev-admin' });

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      // Wire request is { username, password } — partnerId maps to username.
      const callArgs = fetchSpy.mock.calls[0];
      const body = JSON.parse(callArgs[1].body);
      expect(body).toEqual({ username: 'GMEREMIT', password: 'gmepay-dev-admin' });

      expect(out.token).toBe(realJwt);
      expect(out.token.split('.')).toHaveLength(3);
      expect(out.partnerId).toBe('GMEREMIT');
      expect(out.expiresAt).toBe(1750000000);
      expect(out.role).toBe('HUB_ADMIN');
      expect(window.localStorage.getItem(TOKEN_KEY)).toBe(realJwt);
      expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBe('GMEREMIT');
    });

    it('throws a friendly error on 401', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('Unauthorized', { status: 401 })
      );
      await expect(login({ partnerId: 'GMEREMIT', password: 'bad' })).rejects.toThrow(
        /Invalid partner id or password/i
      );
      expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();
    });

    it('throws a generic error on 500', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('Server exploded', { status: 500 })
      );
      await expect(login({ partnerId: 'GMEREMIT', password: 'x' })).rejects.toThrow(
        /Login failed \(HTTP 500\)/i
      );
    });
  });

  describe('logout()', () => {
    it('clears persisted token + partnerId', () => {
      window.localStorage.setItem(TOKEN_KEY, 'tkn');
      window.localStorage.setItem(PARTNER_ID_KEY, 'GMEREMIT');
      logout();
      expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();
      expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBeNull();
    });
  });
});

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
    it('persists token + partnerId on success', async () => {
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(
          JSON.stringify({ token: 'tkn-abc', partnerId: 'GMEREMIT' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        ) as Response
      );

      const out = await login({ partnerId: 'GMEREMIT', password: 'demo' });

      expect(fetchSpy).toHaveBeenCalledTimes(1);
      expect(out.token).toBe('tkn-abc');
      expect(window.localStorage.getItem(TOKEN_KEY)).toBe('tkn-abc');
      expect(window.localStorage.getItem(PARTNER_ID_KEY)).toBe('GMEREMIT');
    });

    it('throws a friendly error on 401', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('Unauthorized', { status: 401 }) as Response
      );
      await expect(
        login({ partnerId: 'GMEREMIT', password: 'bad' })
      ).rejects.toThrow(/Invalid partner id or password/i);
      expect(window.localStorage.getItem(TOKEN_KEY)).toBeNull();
    });

    it('throws a generic error on 500', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('Server exploded', { status: 500 }) as Response
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

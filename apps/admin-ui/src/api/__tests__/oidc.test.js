import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  keycloakBaseUrl,
  isDevLoginAllowed,
  callbackUrl,
  buildAuthRequest,
  startLogin,
  exchangeCode,
  consumeReturnTo,
  decodeJwtPayload,
  logoutUrl,
} from '../oidc';

// vitest.setup.js sets NEXT_PUBLIC_ALLOW_DEV_LOGIN=true; we need explicit
// control here so we can test the non-dev path too.
const originalDevLogin = process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN;

describe('api/oidc (admin-ui)', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN = originalDevLogin;
  });

  // -----------------------------------------------------------------------
  // Config helpers
  // -----------------------------------------------------------------------
  describe('keycloakBaseUrl()', () => {
    it('returns the default when no env is set', () => {
      const saved = process.env.NEXT_PUBLIC_KEYCLOAK_URL;
      delete process.env.NEXT_PUBLIC_KEYCLOAK_URL;
      expect(keycloakBaseUrl()).toBe('http://localhost:8090/realms/gmepay');
      process.env.NEXT_PUBLIC_KEYCLOAK_URL = saved;
    });

    it('honours NEXT_PUBLIC_KEYCLOAK_URL', () => {
      process.env.NEXT_PUBLIC_KEYCLOAK_URL = 'https://kc.example.com/realms/prod';
      expect(keycloakBaseUrl()).toBe('https://kc.example.com/realms/prod');
    });
  });

  describe('isDevLoginAllowed()', () => {
    it('returns true when flag is "true"', () => {
      process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN = 'true';
      expect(isDevLoginAllowed()).toBe(true);
    });

    it('returns false for "1" (typo guard)', () => {
      process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN = '1';
      expect(isDevLoginAllowed()).toBe(false);
    });

    it('returns false when flag is absent', () => {
      delete process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN;
      expect(isDevLoginAllowed()).toBe(false);
    });
  });

  describe('callbackUrl()', () => {
    it('returns origin + /auth/callback', () => {
      expect(callbackUrl()).toMatch(/\/auth\/callback$/);
    });
  });

  // -----------------------------------------------------------------------
  // PKCE challenge generation
  // -----------------------------------------------------------------------
  describe('buildAuthRequest()', () => {
    it('returns a URL with required PKCE params', async () => {
      const url = await buildAuthRequest('/dashboard');
      expect(url).toContain('response_type=code');
      expect(url).toContain('code_challenge_method=S256');
      expect(url).toContain('code_challenge=');
      expect(url).toContain('state=');
      expect(url).toContain('redirect_uri=');
    });

    it('stashes verifier + state + returnTo in sessionStorage', async () => {
      await buildAuthRequest('/some/path');
      expect(window.sessionStorage.getItem('gmepay.oidc.pkceVerifier')).toBeTruthy();
      expect(window.sessionStorage.getItem('gmepay.oidc.state')).toBeTruthy();
      expect(window.sessionStorage.getItem('gmepay.oidc.returnTo')).toBe('/some/path');
    });

    it('generates a different verifier + state on each call', async () => {
      const url1 = await buildAuthRequest();
      const v1 = window.sessionStorage.getItem('gmepay.oidc.pkceVerifier');
      window.sessionStorage.clear();
      const url2 = await buildAuthRequest();
      const v2 = window.sessionStorage.getItem('gmepay.oidc.pkceVerifier');
      expect(v1).not.toBe(v2);
      expect(url1).not.toBe(url2);
    });
  });

  // -----------------------------------------------------------------------
  // startLogin
  // -----------------------------------------------------------------------
  describe('startLogin()', () => {
    it('calls window.location.assign with the Keycloak authorize URL', async () => {
      // jsdom marks location.assign as non-configurable; replace the whole
      // location object to make spying possible.
      const assignSpy = vi.fn();
      Object.defineProperty(window, 'location', {
        value: { ...window.location, assign: assignSpy },
        writable: true,
        configurable: true,
      });
      const url = await startLogin('/protected');
      expect(assignSpy).toHaveBeenCalledWith(url);
      expect(url).toContain('/protocol/openid-connect/auth');
    });
  });

  // -----------------------------------------------------------------------
  // exchangeCode — happy path
  // -----------------------------------------------------------------------
  describe('exchangeCode()', () => {
    async function seedSession(returnTo = '/') {
      await buildAuthRequest(returnTo);
      return {
        state: window.sessionStorage.getItem('gmepay.oidc.state'),
        verifier: window.sessionStorage.getItem('gmepay.oidc.pkceVerifier'),
      };
    }

    it('POSTs to the token endpoint and returns the token response', async () => {
      const { state } = await seedSession('/dashboard');
      const mockTokenResponse = {
        access_token: 'access.jwt',
        id_token: 'id.jwt',
        refresh_token: 'refresh.jwt',
        expires_in: 300,
        token_type: 'Bearer',
        scope: 'openid profile email',
      };
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(JSON.stringify(mockTokenResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      const result = await exchangeCode({ code: 'auth-code-abc', state });

      expect(result.access_token).toBe('access.jwt');
      expect(result.refresh_token).toBe('refresh.jwt');
      // PKCE state cleared after exchange
      expect(window.sessionStorage.getItem('gmepay.oidc.pkceVerifier')).toBeNull();
      expect(window.sessionStorage.getItem('gmepay.oidc.state')).toBeNull();
    });

    it('throws on state mismatch (CSRF)', async () => {
      await seedSession();
      await expect(
        exchangeCode({ code: 'code', state: 'wrong-state' })
      ).rejects.toThrow(/state mismatch/i);
    });

    it('throws when verifier is missing', async () => {
      await buildAuthRequest();
      window.sessionStorage.removeItem('gmepay.oidc.pkceVerifier');
      const state = window.sessionStorage.getItem('gmepay.oidc.state');
      await expect(
        exchangeCode({ code: 'code', state })
      ).rejects.toThrow(/verifier missing/i);
    });

    it('throws on non-2xx token response', async () => {
      const { state } = await seedSession();
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('invalid_grant', { status: 400, statusText: 'Bad Request' })
      );
      await expect(
        exchangeCode({ code: 'bad-code', state })
      ).rejects.toThrow(/Token exchange failed \(400\)/i);
    });
  });

  // -----------------------------------------------------------------------
  // consumeReturnTo
  // -----------------------------------------------------------------------
  describe('consumeReturnTo()', () => {
    it('returns / when nothing is stored', () => {
      expect(consumeReturnTo()).toBe('/');
    });

    it('returns the stored value and then removes it', () => {
      window.sessionStorage.setItem('gmepay.oidc.returnTo', '/admin/partners');
      expect(consumeReturnTo()).toBe('/admin/partners');
      expect(window.sessionStorage.getItem('gmepay.oidc.returnTo')).toBeNull();
    });
  });

  // -----------------------------------------------------------------------
  // decodeJwtPayload
  // -----------------------------------------------------------------------
  describe('decodeJwtPayload()', () => {
    function makeJwt(payload) {
      const enc = (obj) =>
        btoa(JSON.stringify(obj))
          .replace(/\+/g, '-')
          .replace(/\//g, '_')
          .replace(/=+$/, '');
      return `${enc({ alg: 'RS256' })}.${enc(payload)}.fakesig`;
    }

    it('decodes preferred_username from a real-looking JWT', () => {
      const jwt = makeJwt({
        sub: 'u1',
        preferred_username: 'alice',
        realm_access: { roles: ['admin'] },
        exp: Math.floor(Date.now() / 1000) + 3600,
      });
      const claims = decodeJwtPayload(jwt);
      expect(claims.preferred_username).toBe('alice');
      expect(claims.realm_access.roles).toContain('admin');
    });

    it('returns null for invalid input', () => {
      expect(decodeJwtPayload(null)).toBeNull();
      expect(decodeJwtPayload('')).toBeNull();
      expect(decodeJwtPayload('only.two')).toBeNull();
      expect(decodeJwtPayload('bad.????.sig')).toBeNull();
    });
  });

  // -----------------------------------------------------------------------
  // logoutUrl
  // -----------------------------------------------------------------------
  describe('logoutUrl()', () => {
    it('builds the end-session URL with id_token_hint', () => {
      const url = logoutUrl('some.id.token', 'http://localhost:3000/login');
      expect(url).toContain('/protocol/openid-connect/logout');
      expect(url).toContain('id_token_hint=some.id.token');
      expect(url).toContain('post_logout_redirect_uri=');
      expect(url).toContain('client_id=gmepay-admin-ui');
    });

    it('omits id_token_hint when not supplied', () => {
      const url = logoutUrl(null);
      expect(url).not.toContain('id_token_hint');
    });
  });
});

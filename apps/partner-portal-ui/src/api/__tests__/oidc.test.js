import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  keycloakBaseUrl,
  keycloakClientId,
  isDevLoginAllowed,
  callbackUrl,
  buildAuthRequest,
  startLogin,
  exchangeCode,
  refreshTokens,
  consumeReturnTo,
  decodeJwtPayload,
  logoutUrl,
  pkceChallenge,
} from '../oidc';

// vitest.setup.js sets NEXT_PUBLIC_ALLOW_DEV_LOGIN=true for all tests.
// Capture it so we can restore between sub-tests that override it.
const originalDevLogin = process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN;
const originalKcUrl = process.env.NEXT_PUBLIC_KEYCLOAK_URL;
const originalKcClient = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;

describe('api/oidc (partner-portal-ui)', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    process.env.NEXT_PUBLIC_ALLOW_DEV_LOGIN = originalDevLogin;
    if (originalKcUrl !== undefined) {
      process.env.NEXT_PUBLIC_KEYCLOAK_URL = originalKcUrl;
    } else {
      delete process.env.NEXT_PUBLIC_KEYCLOAK_URL;
    }
    if (originalKcClient !== undefined) {
      process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID = originalKcClient;
    } else {
      delete process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;
    }
  });

  // -----------------------------------------------------------------------
  // Config helpers
  // -----------------------------------------------------------------------
  describe('keycloakBaseUrl()', () => {
    it('returns the partners-realm default when no env is set', () => {
      delete process.env.NEXT_PUBLIC_KEYCLOAK_URL;
      expect(keycloakBaseUrl()).toBe(
        'http://localhost:8090/realms/gmepay-partners'
      );
    });

    it('honours NEXT_PUBLIC_KEYCLOAK_URL', () => {
      process.env.NEXT_PUBLIC_KEYCLOAK_URL =
        'https://kc.prod.example.com/realms/partners';
      expect(keycloakBaseUrl()).toBe(
        'https://kc.prod.example.com/realms/partners'
      );
    });
  });

  describe('keycloakClientId()', () => {
    it('returns the default partner client id', () => {
      delete process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID;
      expect(keycloakClientId()).toBe('gmepay-partner-ui');
    });

    it('honours NEXT_PUBLIC_KEYCLOAK_CLIENT_ID', () => {
      process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID = 'custom-client';
      expect(keycloakClientId()).toBe('custom-client');
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
    it('ends with /auth/callback', () => {
      expect(callbackUrl()).toMatch(/\/auth\/callback$/);
    });
  });

  // -----------------------------------------------------------------------
  // PKCE challenge
  // -----------------------------------------------------------------------
  describe('pkceChallenge()', () => {
    it('returns a non-empty base64url string for a verifier', async () => {
      const challenge = await pkceChallenge('myverifier123');
      expect(challenge).toBeTruthy();
      expect(challenge).toMatch(/^[A-Za-z0-9_-]+$/);
    });

    it('produces a different challenge for different verifiers', async () => {
      const c1 = await pkceChallenge('verifier-aaa');
      const c2 = await pkceChallenge('verifier-bbb');
      expect(c1).not.toBe(c2);
    });
  });

  // -----------------------------------------------------------------------
  // buildAuthRequest
  // -----------------------------------------------------------------------
  describe('buildAuthRequest()', () => {
    it('returns a URL with required PKCE + OIDC params', async () => {
      const url = await buildAuthRequest('/balance');
      expect(url).toContain('response_type=code');
      expect(url).toContain('code_challenge_method=S256');
      expect(url).toContain('code_challenge=');
      expect(url).toContain('state=');
      expect(url).toContain('scope=openid');
      expect(url).toContain('redirect_uri=');
    });

    it('stashes verifier + state + returnTo in sessionStorage', async () => {
      await buildAuthRequest('/transactions');
      expect(
        window.sessionStorage.getItem('gmepay.portal.oidc.pkceVerifier')
      ).toBeTruthy();
      expect(
        window.sessionStorage.getItem('gmepay.portal.oidc.state')
      ).toBeTruthy();
      expect(
        window.sessionStorage.getItem('gmepay.portal.oidc.returnTo')
      ).toBe('/transactions');
    });

    it('generates unique verifier + state on each call', async () => {
      await buildAuthRequest();
      const v1 = window.sessionStorage.getItem(
        'gmepay.portal.oidc.pkceVerifier'
      );
      window.sessionStorage.clear();
      await buildAuthRequest();
      const v2 = window.sessionStorage.getItem(
        'gmepay.portal.oidc.pkceVerifier'
      );
      expect(v1).not.toBe(v2);
    });
  });

  // -----------------------------------------------------------------------
  // startLogin
  // -----------------------------------------------------------------------
  describe('startLogin()', () => {
    it('calls window.location.assign with the authorize URL', async () => {
      // jsdom marks location.assign as non-configurable; replace the whole
      // location object to make recording possible.
      const assignSpy = vi.fn();
      Object.defineProperty(window, 'location', {
        value: { ...window.location, assign: assignSpy },
        writable: true,
        configurable: true,
      });
      const url = await startLogin('/balance');
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
        state: window.sessionStorage.getItem('gmepay.portal.oidc.state'),
      };
    }

    it('POSTs to token endpoint and returns parsed response', async () => {
      const { state } = await seedSession('/balance');
      const mockTokenResponse = {
        access_token: 'access.partner.jwt',
        id_token: 'id.partner.jwt',
        refresh_token: 'refresh.partner.jwt',
        expires_in: 300,
        token_type: 'Bearer',
      };
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(JSON.stringify(mockTokenResponse), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      const result = await exchangeCode({ code: 'auth-code-xyz', state });

      expect(result.access_token).toBe('access.partner.jwt');
      expect(result.refresh_token).toBe('refresh.partner.jwt');
      // Session storage cleared after successful exchange
      expect(
        window.sessionStorage.getItem('gmepay.portal.oidc.pkceVerifier')
      ).toBeNull();
      expect(
        window.sessionStorage.getItem('gmepay.portal.oidc.state')
      ).toBeNull();
    });

    it('throws on state mismatch (CSRF defense)', async () => {
      await seedSession();
      await expect(
        exchangeCode({ code: 'code', state: 'tampered-state' })
      ).rejects.toThrow(/state mismatch/i);
    });

    it('throws when PKCE verifier is missing', async () => {
      await buildAuthRequest();
      window.sessionStorage.removeItem('gmepay.portal.oidc.pkceVerifier');
      const state = window.sessionStorage.getItem('gmepay.portal.oidc.state');
      await expect(
        exchangeCode({ code: 'code', state })
      ).rejects.toThrow(/verifier missing/i);
    });

    it('throws on non-2xx token endpoint response', async () => {
      const { state } = await seedSession();
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('invalid_grant', {
          status: 400,
          statusText: 'Bad Request',
        })
      );
      await expect(
        exchangeCode({ code: 'bad-code', state })
      ).rejects.toThrow(/Token exchange failed \(400\)/i);
    });
  });

  // -----------------------------------------------------------------------
  // refreshTokens
  // -----------------------------------------------------------------------
  describe('refreshTokens()', () => {
    it('POSTs grant_type=refresh_token and returns new token response', async () => {
      const newTokens = {
        access_token: 'new.access.jwt',
        expires_in: 300,
        refresh_token: 'new.refresh.jwt',
      };
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response(JSON.stringify(newTokens), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );

      const result = await refreshTokens('old.refresh.jwt');

      const call = globalThis.fetch.mock.calls[0];
      const bodyStr = call[1].body;
      expect(bodyStr).toContain('grant_type=refresh_token');
      expect(bodyStr).toContain('refresh_token=old.refresh.jwt');
      expect(result.access_token).toBe('new.access.jwt');
    });

    it('throws on non-2xx refresh response', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue(
        new Response('invalid_token', { status: 401, statusText: 'Unauthorized' })
      );
      await expect(refreshTokens('expired.token')).rejects.toThrow(
        /Token refresh failed \(401\)/i
      );
    });

    it('throws when no refresh token is provided', async () => {
      await expect(refreshTokens(null)).rejects.toThrow(/No refresh token/i);
      await expect(refreshTokens('')).rejects.toThrow(/No refresh token/i);
    });
  });

  // -----------------------------------------------------------------------
  // consumeReturnTo
  // -----------------------------------------------------------------------
  describe('consumeReturnTo()', () => {
    it('returns / when nothing is stored', () => {
      expect(consumeReturnTo()).toBe('/');
    });

    it('returns stored value then removes it', () => {
      window.sessionStorage.setItem(
        'gmepay.portal.oidc.returnTo',
        '/transactions'
      );
      expect(consumeReturnTo()).toBe('/transactions');
      expect(
        window.sessionStorage.getItem('gmepay.portal.oidc.returnTo')
      ).toBeNull();
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

    it('decodes a payload with preferred_username', () => {
      const jwt = makeJwt({
        sub: 'p1',
        preferred_username: 'GMEREMIT',
        partner_id: 'GMEREMIT',
        exp: Math.floor(Date.now() / 1000) + 3600,
      });
      const claims = decodeJwtPayload(jwt);
      expect(claims.preferred_username).toBe('GMEREMIT');
      expect(claims.partner_id).toBe('GMEREMIT');
    });

    it('returns null for null/empty/malformed input', () => {
      expect(decodeJwtPayload(null)).toBeNull();
      expect(decodeJwtPayload('')).toBeNull();
      expect(decodeJwtPayload('one.two')).toBeNull();
      expect(decodeJwtPayload('bad.!!.sig')).toBeNull();
    });
  });

  // -----------------------------------------------------------------------
  // logoutUrl
  // -----------------------------------------------------------------------
  describe('logoutUrl()', () => {
    it('builds end-session URL with id_token_hint', () => {
      const url = logoutUrl('id.token.here', 'http://localhost:3001/login');
      expect(url).toContain('/protocol/openid-connect/logout');
      expect(url).toContain('id_token_hint=id.token.here');
      expect(url).toContain('post_logout_redirect_uri=');
      expect(url).toContain('client_id=gmepay-partner-ui');
    });

    it('omits id_token_hint when not supplied', () => {
      const url = logoutUrl(null);
      expect(url).not.toContain('id_token_hint');
    });
  });
});

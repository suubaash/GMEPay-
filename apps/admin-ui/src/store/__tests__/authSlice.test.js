/**
 * Contract lock for auth state.
 *
 * Two paths are exercised:
 *   1. Dev-skip BFF login (NEXT_PUBLIC_ALLOW_DEV_LOGIN=true) — POST /v1/auth/login
 *      returns `record LoginResponse(String token, Instant expiresAt, String role)`
 *      from ops-partner-bff. BFF does NOT echo username; we cache the form
 *      input alongside the token so AppShell can render the signed-in identity.
 *   2. Keycloak OIDC — admin-ui receives Keycloak's /token response and the
 *      `applyOidcSessionThunk` mirrors the relevant claims into the slice.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  loginThunk,
  logout,
  hydrate,
  applyOidcSessionThunk,
} from '@/store/authSlice';

const TOKEN = 'mock.eyJhYmM';
const EXPIRES = '2026-06-09T13:34:56Z';

describe('authSlice', () => {
  it('stores token + expiresAt + role + cached username on dev-skip login success', () => {
    const next = reducer(undefined, {
      type: loginThunk.fulfilled.type,
      payload: {
        token: TOKEN,
        expiresAt: EXPIRES,
        role: 'ADMIN',
        username: 'admin',
      },
    });
    expect(next.token).toBe(TOKEN);
    expect(next.expiresAt).toBe(EXPIRES);
    expect(next.role).toBe('ADMIN');
    expect(next.username).toBe('admin');
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
  });

  it('records the error message on rejected', () => {
    const next = reducer(undefined, {
      type: loginThunk.rejected.type,
      payload: 'invalid credentials',
      error: { message: 'Request failed' },
    });
    expect(next.token).toBeNull();
    expect(next.error).toBe('invalid credentials');
    expect(next.loading).toBe(false);
  });

  it('clears the slice on logout', () => {
    const start = reducer(undefined, {
      type: loginThunk.fulfilled.type,
      payload: { token: TOKEN, expiresAt: EXPIRES, role: 'ADMIN', username: 'admin' },
    });
    const next = reducer(start, logout());
    expect(next.token).toBeNull();
    expect(next.username).toBeNull();
    expect(next.role).toBeNull();
    expect(next.expiresAt).toBeNull();
  });

  it('hydrates the slice from localStorage payload', () => {
    const next = reducer(undefined, hydrate({
      token: TOKEN,
      username: 'admin',
      role: 'ADMIN',
      expiresAt: EXPIRES,
    }));
    expect(next.token).toBe(TOKEN);
    expect(next.username).toBe('admin');
    expect(next.role).toBe('ADMIN');
    expect(next.expiresAt).toBe(EXPIRES);
  });

  it('applies an OIDC session payload onto the slice', () => {
    const next = reducer(undefined, {
      type: applyOidcSessionThunk.fulfilled.type,
      payload: {
        token: 'kc-access-token',
        username: 'subash',
        role: 'gmepay-admin',
        expiresAt: '9999999999999',
      },
    });
    expect(next.token).toBe('kc-access-token');
    expect(next.username).toBe('subash');
    expect(next.role).toBe('gmepay-admin');
    expect(next.expiresAt).toBe('9999999999999');
    expect(next.error).toBeNull();
  });
});

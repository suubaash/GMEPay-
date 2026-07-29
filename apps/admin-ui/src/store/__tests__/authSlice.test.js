/**
 * Contract lock for auth state.
 *
 * There is ONE path in: Keycloak OIDC. The admin-ui receives Keycloak's /token
 * response and `applyOidcSessionThunk` mirrors the relevant claims into the
 * slice. The dev-skip `loginThunk` (POST /v1/auth/login on ops-partner-bff) is
 * gone along with the endpoint itself (gap T0-1) — a token the BFF signed for
 * itself is rejected by its own resource-server filter chain now.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  logout,
  hydrate,
  applyOidcSessionThunk,
} from '@/store/authSlice';

const TOKEN = 'kc-access-token';
const EXPIRES = '9999999999999';

describe('authSlice', () => {
  it('does not export a password login thunk any more', async () => {
    const mod = await import('@/store/authSlice');
    expect(mod.loginThunk).toBeUndefined();
  });

  it('applies an OIDC session payload onto the slice', () => {
    const next = reducer(undefined, {
      type: applyOidcSessionThunk.fulfilled.type,
      payload: {
        token: TOKEN,
        username: 'subash',
        role: 'OPERATOR',
        expiresAt: EXPIRES,
      },
    });
    expect(next.token).toBe(TOKEN);
    expect(next.username).toBe('subash');
    expect(next.role).toBe('OPERATOR');
    expect(next.expiresAt).toBe(EXPIRES);
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
  });

  it('records the error message on a rejected session apply', () => {
    const next = reducer(undefined, {
      type: applyOidcSessionThunk.rejected.type,
      payload: 'OIDC state mismatch — refusing token exchange',
      error: { message: 'Request failed' },
    });
    expect(next.token).toBeNull();
    expect(next.error).toBe('OIDC state mismatch — refusing token exchange');
    expect(next.loading).toBe(false);
  });

  it('clears the slice on logout', () => {
    const start = reducer(undefined, {
      type: applyOidcSessionThunk.fulfilled.type,
      payload: { token: TOKEN, expiresAt: EXPIRES, role: 'OPERATOR', username: 'admin' },
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
      role: 'OPERATOR',
      expiresAt: EXPIRES,
    }));
    expect(next.token).toBe(TOKEN);
    expect(next.username).toBe('admin');
    expect(next.role).toBe('OPERATOR');
    expect(next.expiresAt).toBe(EXPIRES);
  });
});

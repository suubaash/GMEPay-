import { describe, it, expect, vi } from 'vitest';

// The slice imports auth helpers; stub them so no localStorage/network is touched.
vi.mock('@/api/auth', () => ({
  getToken: () => null,
  getPartnerId: () => null,
  logout: vi.fn(),
  clearAuth: vi.fn(),
  storeOidcSession: vi.fn(),
  // T1-2: partner scope comes from the token's partner_id claim.
  partnerIdFromTokens: () => 'GMEREMIT',
  EXPIRES_AT_KEY: 'gmepay.partnerTokenExpiresAt'
}));

import reducer, {
  applyOidcSessionThunk,
  hydrateFromStorage,
  logoutAction,
  clearAuthError
} from '../authSlice';

/**
 * Contract lock: there is exactly ONE way into this slice — a Keycloak token
 * response applied by `applyOidcSessionThunk` from the /auth/callback page.
 * `loginThunk` (POST /v1/auth/login, `password=demo`) is gone with the endpoint
 * it called (T0-1), so a re-appearing password path would fail to compile here.
 */
describe('authSlice', () => {
  it('starts unauthenticated', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual({
      partnerId: null,
      token: null,
      role: null,
      expiresAt: null,
      status: 'idle',
      error: null
    });
  });

  it('does not export a password login thunk any more', async () => {
    const mod = await import('../authSlice');
    expect(mod.loginThunk).toBeUndefined();
  });

  it('stores token + partnerId + role from an applied OIDC session', () => {
    const state = reducer(undefined, {
      type: applyOidcSessionThunk.fulfilled.type,
      payload: {
        token: 'kc-access-token',
        partnerId: 'GMEREMIT', // from the partner_id claim, never a form field
        role: 'PARTNER_USER',
        expiresAt: '9999999999999'
      }
    });
    expect(state.status).toBe('succeeded');
    expect(state.token).toBe('kc-access-token');
    expect(state.partnerId).toBe('GMEREMIT');
    expect(state.role).toBe('PARTNER_USER');
    expect(state.expiresAt).toBe('9999999999999');
    expect(state.error).toBeNull();
  });

  it('captures the error from rejectWithValue on failure', () => {
    const state = reducer(undefined, {
      type: applyOidcSessionThunk.rejected.type,
      payload: 'OIDC state mismatch — refusing token exchange',
      error: { message: 'rejected' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('OIDC state mismatch — refusing token exchange');
  });

  it('clearAuthError leaves token alone', () => {
    const seeded = {
      partnerId: 'GMEREMIT',
      token: 'tkn',
      role: 'PARTNER_USER',
      status: 'failed',
      error: 'bad'
    };
    expect(reducer(seeded, clearAuthError())).toEqual({
      ...seeded,
      error: null
    });
  });

  it('logoutAction wipes all credentials', () => {
    const seeded = {
      partnerId: 'GMEREMIT',
      token: 'tkn',
      role: 'PARTNER_USER',
      status: 'succeeded',
      error: null
    };
    const after = reducer(seeded, logoutAction());
    expect(after.token).toBeNull();
    expect(after.partnerId).toBeNull();
    expect(after.role).toBeNull();
    expect(after.status).toBe('idle');
  });

  it('hydrateFromStorage stays idle when storage is empty', () => {
    const state = reducer(undefined, hydrateFromStorage());
    expect(state.token).toBeNull();
    expect(state.partnerId).toBeNull();
    expect(state.status).toBe('idle');
  });
});

import { describe, it, expect, vi } from 'vitest';

// authSlice imports portalApi + auth helpers; stub them to avoid real fetches.
vi.mock('@/api/client', () => ({
  portalApi: { login: vi.fn() }
}));
vi.mock('@/api/auth', () => ({
  getToken: () => null,
  getPartnerId: () => null,
  logout: vi.fn()
}));

import reducer, {
  loginThunk,
  hydrateFromStorage,
  logoutAction,
  clearAuthError
} from '../authSlice';

/**
 * Contract lock: the BFF LoginResponse is `{ token, expiresAt, role }` —
 * there is NO partnerId on the wire. `api/auth.login()` adapter mirrors the
 * form's partnerId onto the LoginResponse so the slice has a stable partner
 * identity. This test exercises the post-adapter shape only.
 */
describe('authSlice', () => {
  it('starts unauthenticated', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual({
      partnerId: null,
      token: null,
      role: null,
      status: 'idle',
      error: null
    });
  });

  it('stores token + partnerId + role on login success', () => {
    const state = reducer(undefined, {
      type: loginThunk.fulfilled.type,
      payload: {
        token: 'mock.eyJabc',
        partnerId: 'GMEREMIT', // mirrored from the form (NOT from the wire)
        expiresAt: '2026-06-09T13:15:30Z',
        role: 'ADMIN'
      }
    });
    expect(state.status).toBe('succeeded');
    expect(state.token).toBe('mock.eyJabc');
    expect(state.partnerId).toBe('GMEREMIT');
    expect(state.role).toBe('ADMIN');
    expect(state.error).toBeNull();
  });

  it('captures the error from rejectWithValue on failure', () => {
    const state = reducer(undefined, {
      type: loginThunk.rejected.type,
      payload: 'Invalid partner id or password',
      error: { message: 'rejected' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('Invalid partner id or password');
  });

  it('clearAuthError leaves token alone', () => {
    const seeded = {
      partnerId: 'GMEREMIT',
      token: 'tkn',
      role: 'ADMIN',
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
      role: 'ADMIN',
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

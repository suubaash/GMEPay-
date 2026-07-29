import { describe, it, expect } from 'vitest';
import reducer, { fetchApiKeys, resetApiKeys } from '../apiKeysSlice';

/**
 * Contract lock: the apiKeys reducer must persist the BFF wire shape
 * verbatim. If the BFF DTO drifts, this test breaks before the page does.
 *
 * Wire shape: Array<ApiKeyView>
 *   { keyId, name, prefix, scopes[], createdAt, lastUsedAt, status }
 */
describe('apiKeysSlice', () => {
  it('starts idle with no data', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual({
      data: null,
      status: 'idle',
      error: null
    });
  });

  it('marks loading when the thunk starts', () => {
    const state = reducer(undefined, { type: fetchApiKeys.pending.type });
    expect(state.status).toBe('loading');
    expect(state.error).toBeNull();
  });

  it('stores the BFF api-keys list verbatim on success', () => {
    const wire = [
      // The REAL wire shape from auth-identity's api_keys registry (gap T1-3).
      {
        keyId: 'k_01HXYZACTIVE',
        name: null,
        prefix: 'gmepk_live_abcd1234',
        scopes: [],
        createdAt: '2026-01-15T08:00:00Z',
        lastUsedAt: null,
        status: 'ACTIVE',
        environment: 'PRODUCTION',
        expiresAt: null
      },
      {
        keyId: 'k_01HXYZSBX',
        name: null,
        prefix: 'gmepk_live_efgh5678',
        scopes: [],
        createdAt: '2026-05-30T08:00:00Z',
        lastUsedAt: null,
        status: 'REVOKED',
        environment: 'SANDBOX',
        expiresAt: null
      }
    ];
    const state = reducer(undefined, {
      type: fetchApiKeys.fulfilled.type,
      payload: wire
    });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);
    // The slice is a pass-through: the honestly-absent fields must survive as
    // null/empty rather than being defaulted to something renderable.
    expect(state.data[0].name).toBeNull();
    expect(state.data[0].scopes).toEqual([]);
    expect(state.data[0].lastUsedAt).toBeNull();
    expect(state.data[0].environment).toBe('PRODUCTION');
    expect(state.data[1].status).toBe('REVOKED');
  });

  it('coerces non-array payloads to []', () => {
    const state = reducer(undefined, {
      type: fetchApiKeys.fulfilled.type,
      payload: null
    });
    expect(state.data).toEqual([]);
  });

  it('captures the error on rejection', () => {
    const state = reducer(undefined, {
      type: fetchApiKeys.rejected.type,
      error: { message: 'boom' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('boom');
  });

  it('resetApiKeys returns to initial state', () => {
    const dirty = {
      data: [{ keyId: 'x', name: 'x', prefix: 'x', scopes: [], createdAt: '', lastUsedAt: null, status: 'ACTIVE' }],
      status: 'succeeded',
      error: null
    };
    expect(reducer(dirty, resetApiKeys())).toEqual({
      data: null,
      status: 'idle',
      error: null
    });
  });
});

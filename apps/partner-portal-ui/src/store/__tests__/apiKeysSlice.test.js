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
      {
        keyId: 'k_01HXYZACTIVE',
        name: 'Production',
        prefix: 'gmepk_live_abcd1234',
        scopes: ['payments:create', 'payments:read'],
        createdAt: '2026-01-15T08:00:00Z',
        lastUsedAt: '2026-06-09T11:24:00Z',
        status: 'ACTIVE'
      },
      {
        keyId: 'k_01HXYZROT',
        name: 'Production (rotating)',
        prefix: 'gmepk_live_efgh5678',
        scopes: ['payments:create'],
        createdAt: '2026-05-30T08:00:00Z',
        lastUsedAt: null,
        status: 'ROTATING'
      }
    ];
    const state = reducer(undefined, {
      type: fetchApiKeys.fulfilled.type,
      payload: wire
    });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);
    expect(state.data[0].scopes).toEqual(['payments:create', 'payments:read']);
    expect(state.data[1].status).toBe('ROTATING');
    expect(state.data[1].lastUsedAt).toBeNull();
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

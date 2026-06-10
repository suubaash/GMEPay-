import { describe, it, expect } from 'vitest';
import reducer, { fetchProfile } from '../profileSlice';

/**
 * Contract lock: profile reducer stores the BFF PartnerProfile verbatim.
 *
 * Wire shape: { partnerId, type, settlementCurrency, settlementRoundingMode, onboardedAt }
 * NO `displayName` field.
 */
describe('profileSlice', () => {
  it('starts idle with no data', () => {
    expect(reducer(undefined, { type: '@@INIT' })).toEqual({
      data: null,
      status: 'idle',
      error: null
    });
  });

  it('stores the BFF PartnerProfile verbatim', () => {
    const wire = {
      partnerId: 'GMEREMIT',
      type: 'OVERSEAS',
      settlementCurrency: 'USD',
      settlementRoundingMode: 'HALF_UP',
      onboardedAt: '2026-01-01T00:00:00Z'
    };
    const state = reducer(undefined, { type: fetchProfile.fulfilled.type, payload: wire });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);
    expect(state.data.settlementRoundingMode).toBe('HALF_UP');
  });

  it('captures errors', () => {
    const state = reducer(undefined, {
      type: fetchProfile.rejected.type,
      error: { message: 'fail' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('fail');
  });
});

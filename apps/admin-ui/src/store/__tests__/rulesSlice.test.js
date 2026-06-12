/**
 * Contract lock for the rulesSlice reducers and thunks (Slice 6A.2).
 *
 * Tests:
 *  - Initial state shape
 *  - fetchRules: pending → fulfilled → rejected
 *  - patchRules: pending → fulfilled → rejected
 *  - clearRulesError / setLocalRules reducers
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchRules,
  patchRules,
  clearRulesError,
  setLocalRules,
} from '@/store/rulesSlice';

const SAMPLE_RULES = [
  {
    id:               1,
    schemeId:         'ZEROPAY',
    direction:        'OUTBOUND',
    mA:               '0.0150',
    mB:               '0.0050',
    serviceChargeUsd: '0.0000',
    validFrom:        '2026-01-01T00:00:00Z',
    validTo:          null,
    recordedAt:       '2026-01-01T00:00:00Z',
  },
  {
    id:               2,
    schemeId:         'VIETQR',
    direction:        'INBOUND',
    mA:               '0.0200',
    mB:               '0.0100',
    serviceChargeUsd: '1.0000',
    validFrom:        '2026-01-01T00:00:00Z',
    validTo:          null,
    recordedAt:       '2026-01-01T00:00:00Z',
  },
];

describe('rulesSlice — initial state', () => {
  it('has the correct initial state shape', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state.rulesByCode).toEqual({});
    expect(state.loadingByCode).toEqual({});
    expect(state.saving).toBe(false);
    expect(state.error).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// fetchRules
// ─────────────────────────────────────────────────────────────────────────────

describe('rulesSlice — fetchRules', () => {
  it('sets loading=true on pending', () => {
    const state = reducer(undefined, {
      type:  fetchRules.pending.type,
      meta:  { arg: 'GME_KR_001' },
    });
    expect(state.loadingByCode['GME_KR_001']).toBe(true);
    expect(state.error).toBeNull();
  });

  it('stores rules by partnerCode on fulfilled', () => {
    const state = reducer(undefined, {
      type:    fetchRules.fulfilled.type,
      payload: { partnerCode: 'GME_KR_001', rules: SAMPLE_RULES },
    });
    expect(state.loadingByCode['GME_KR_001']).toBe(false);
    expect(state.rulesByCode['GME_KR_001']).toHaveLength(2);
    expect(state.rulesByCode['GME_KR_001'][0].schemeId).toBe('ZEROPAY');
  });

  it('stores empty array when BFF returns empty list', () => {
    const state = reducer(undefined, {
      type:    fetchRules.fulfilled.type,
      payload: { partnerCode: 'GME_VN_001', rules: [] },
    });
    expect(state.rulesByCode['GME_VN_001']).toEqual([]);
  });

  it('falls back to empty array when payload rules is null', () => {
    const state = reducer(undefined, {
      type:    fetchRules.fulfilled.type,
      payload: { partnerCode: 'GME_VN_002', rules: null },
    });
    expect(state.rulesByCode['GME_VN_002']).toEqual([]);
  });

  it('sets error and clears loading on rejected (non-fatal)', () => {
    const state = reducer(undefined, {
      type:  fetchRules.rejected.type,
      meta:  { arg: 'GME_KR_001' },
      error: { message: 'Network error' },
    });
    expect(state.loadingByCode['GME_KR_001']).toBe(false);
    expect(state.error).toBe('Network error');
  });

  it('uses fallback error message when error.message is absent', () => {
    const state = reducer(undefined, {
      type:  fetchRules.rejected.type,
      meta:  { arg: 'GME_KR_001' },
      error: {},
    });
    expect(state.error).toBe('Failed to load pricing rules');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// patchRules
// ─────────────────────────────────────────────────────────────────────────────

describe('rulesSlice — patchRules', () => {
  it('sets saving=true on pending', () => {
    const state = reducer(undefined, { type: patchRules.pending.type });
    expect(state.saving).toBe(true);
    expect(state.error).toBeNull();
  });

  it('stores the updated rule set and clears saving on fulfilled', () => {
    const state = reducer(undefined, {
      type:    patchRules.fulfilled.type,
      payload: { partnerCode: 'GME_KR_001', rules: SAMPLE_RULES },
    });
    expect(state.saving).toBe(false);
    expect(state.rulesByCode['GME_KR_001']).toHaveLength(2);
    expect(state.rulesByCode['GME_KR_001'][1].schemeId).toBe('VIETQR');
  });

  it('falls back to empty array when fulfilled payload rules is null', () => {
    const state = reducer(undefined, {
      type:    patchRules.fulfilled.type,
      payload: { partnerCode: 'GME_KR_001', rules: null },
    });
    expect(state.rulesByCode['GME_KR_001']).toEqual([]);
  });

  it('sets error and clears saving on rejected', () => {
    const state = reducer(undefined, {
      type:  patchRules.rejected.type,
      error: { message: 'Margin floor violation' },
    });
    expect(state.saving).toBe(false);
    expect(state.error).toBe('Margin floor violation');
  });

  it('uses fallback error message when error.message is absent', () => {
    const state = reducer(undefined, {
      type:  patchRules.rejected.type,
      error: {},
    });
    expect(state.error).toBe('Failed to save pricing rules');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// reducers: clearRulesError + setLocalRules
// ─────────────────────────────────────────────────────────────────────────────

describe('rulesSlice — synchronous reducers', () => {
  it('clearRulesError nullifies error', () => {
    const withError = reducer(undefined, {
      type:  patchRules.rejected.type,
      error: { message: 'oops' },
    });
    expect(withError.error).toBe('oops');

    const cleared = reducer(withError, clearRulesError());
    expect(cleared.error).toBeNull();
  });

  it('setLocalRules writes rules into rulesByCode map', () => {
    const state = reducer(undefined, setLocalRules({
      partnerCode: 'GME_SG_001',
      rules:       SAMPLE_RULES,
    }));
    expect(state.rulesByCode['GME_SG_001']).toHaveLength(2);
    expect(state.rulesByCode['GME_SG_001'][0].direction).toBe('OUTBOUND');
  });

  it('setLocalRules accepts an empty array (clear)', () => {
    const state = reducer(undefined, setLocalRules({
      partnerCode: 'GME_SG_002',
      rules:       [],
    }));
    expect(state.rulesByCode['GME_SG_002']).toEqual([]);
  });
});

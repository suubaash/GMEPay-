/**
 * Slice 1 wizard contract — locks the shape of state.drafts so the wizard
 * UI doesn't drift away from what the BFF returns (PartnerView) and so
 * the per-step thunks all hydrate `current` consistently.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  clearError,
  createDraft,
  fetchDraft,
  fetchDrafts,
  patchStep1,
  patchStep3,
  resetCurrent,
} from '@/store/draftsSlice';

const DRAFT_VIEW = {
  id: 42,
  partnerCode: 'draft_partner_001',
  status: 'ONBOARDING',
  type: 'OVERSEAS',
  settlementCurrency: 'USD',
  settlementRoundingMode: 'HALF_UP',
  legalNameLocal: null,
  legalNameRomanized: null,
};

describe('draftsSlice', () => {
  it('hydrates state.current on fetchDraft.fulfilled', () => {
    const next = reducer(undefined, {
      type: fetchDraft.fulfilled.type,
      payload: DRAFT_VIEW,
    });
    expect(next.current).toEqual(DRAFT_VIEW);
    expect(next.currentCode).toBe('draft_partner_001');
    expect(next.loading).toBe(false);
  });

  it('records currentCode from fetchDraft.pending so the URL is the source of truth', () => {
    const next = reducer(undefined, {
      type: fetchDraft.pending.type,
      meta: { arg: 'draft_partner_007' },
    });
    expect(next.currentCode).toBe('draft_partner_007');
    expect(next.loading).toBe(true);
    expect(next.error).toBeNull();
  });

  it('replaces state.list on fetchDrafts.fulfilled and clears listLoading', () => {
    const next = reducer(undefined, {
      type: fetchDrafts.fulfilled.type,
      payload: [DRAFT_VIEW],
    });
    expect(next.list).toHaveLength(1);
    expect(next.list[0].partnerCode).toBe('draft_partner_001');
    expect(next.listLoading).toBe(false);
  });

  it('prepends a created draft into the list and makes it current', () => {
    const start = reducer(undefined, {
      type: fetchDrafts.fulfilled.type,
      payload: [{ ...DRAFT_VIEW, partnerCode: 'older' }],
    });
    const next = reducer(start, {
      type: createDraft.fulfilled.type,
      payload: DRAFT_VIEW,
    });
    expect(next.current).toEqual(DRAFT_VIEW);
    expect(next.list[0].partnerCode).toBe('draft_partner_001');
    expect(next.list[1].partnerCode).toBe('older');
    expect(next.saving).toBe(false);
  });

  it('refreshes current with the payload returned by patchStep1.fulfilled', () => {
    const start = reducer(undefined, {
      type: fetchDraft.fulfilled.type,
      payload: DRAFT_VIEW,
    });
    const updated = { ...DRAFT_VIEW, legalNameRomanized: 'GME Co., Ltd.' };
    const next = reducer(start, {
      type: patchStep1.fulfilled.type,
      payload: updated,
    });
    expect(next.current.legalNameRomanized).toBe('GME Co., Ltd.');
    expect(next.saving).toBe(false);
  });

  it('surfaces patchStep3.rejected error (Step 3 not yet implemented in Slice 1)', () => {
    const next = reducer(undefined, {
      type: patchStep3.rejected.type,
      error: { message: 'Step 3 is not implemented yet — coming in a later Slice.' },
    });
    expect(next.saving).toBe(false);
    expect(next.error).toMatch(/Step 3/);
  });

  it('clearError wipes the error without touching other state', () => {
    const start = reducer(undefined, {
      type: fetchDraft.rejected.type,
      error: { message: 'boom' },
    });
    expect(start.error).toBe('boom');
    const next = reducer(start, clearError());
    expect(next.error).toBeNull();
  });

  it('resetCurrent clears current/currentCode (used on wizard unmount)', () => {
    const start = reducer(undefined, {
      type: fetchDraft.fulfilled.type,
      payload: DRAFT_VIEW,
    });
    expect(start.current).not.toBeNull();
    const next = reducer(start, resetCurrent());
    expect(next.current).toBeNull();
    expect(next.currentCode).toBeNull();
  });
});

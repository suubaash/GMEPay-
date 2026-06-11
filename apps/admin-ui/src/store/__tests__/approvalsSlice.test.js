/**
 * Contract lock for approvalsSlice (Slice 2, agent 2B.2).
 *
 * Covers:
 *  - fetchPending: populates items array.
 *  - approve.fulfilled: removes the approved row from items.
 *  - approve.rejected: stores actError; acting cleared.
 *  - reject.fulfilled: removes the rejected row from items.
 *  - reject requires reason (business rule tested in page.test.jsx; here we
 *    confirm the slice removes the row on .fulfilled regardless of reason).
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchPending,
  approve,
  reject,
  clearActError,
} from '@/store/approvalsSlice';

const CR_LIST = [
  {
    id: 1,
    aggregate: 'Partner:GME_KR_001',
    proposer: 'bob',
    proposedAt: '2026-06-10T09:00:00Z',
    payload: { legalNameLocal: 'GME Korea' },
  },
  {
    id: 2,
    aggregate: 'Partner:GME_VN_002',
    proposer: 'alice',
    proposedAt: '2026-06-10T10:30:00Z',
    payload: { countryOfIncorporation: 'VN' },
  },
];

describe('approvalsSlice', () => {
  it('fetchPending.fulfilled stores payload as items', () => {
    const next = reducer(undefined, {
      type: fetchPending.fulfilled.type,
      payload: CR_LIST,
    });
    expect(next.items).toEqual(CR_LIST);
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
  });

  it('fetchPending.pending sets loading true and clears error', () => {
    const start = reducer(undefined, {
      type: fetchPending.rejected.type,
      error: { message: 'timeout' },
    });
    const next = reducer(start, { type: fetchPending.pending.type });
    expect(next.loading).toBe(true);
    expect(next.error).toBeNull();
  });

  it('fetchPending.rejected stores error message', () => {
    const next = reducer(undefined, {
      type: fetchPending.rejected.type,
      error: { message: 'Network error' },
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBe('Network error');
  });

  it('approve.pending sets acting[id] = "approving"', () => {
    const next = reducer(undefined, {
      type: approve.pending.type,
      meta: { arg: { id: 1, approvedBy: 'alice' } },
    });
    expect(next.acting[1]).toBe('approving');
  });

  it('approve.fulfilled removes the approved row from items', () => {
    const start = reducer(undefined, {
      type: fetchPending.fulfilled.type,
      payload: CR_LIST,
    });
    expect(start.items).toHaveLength(2);

    const next = reducer(start, {
      type: approve.fulfilled.type,
      meta: { arg: { id: 1, approvedBy: 'alice' } },
      payload: { id: 1, state: 'APPROVED' },
    });
    expect(next.items).toHaveLength(1);
    expect(next.items[0].id).toBe(2);
    expect(next.acting[1]).toBeUndefined();
  });

  it('approve.rejected stores actError and clears acting', () => {
    const withPending = reducer(undefined, {
      type: approve.pending.type,
      meta: { arg: { id: 1, approvedBy: 'alice' } },
    });
    const next = reducer(withPending, {
      type: approve.rejected.type,
      meta: { arg: { id: 1, approvedBy: 'alice' } },
      payload: { id: 1, message: '4-eyes violation' },
    });
    expect(next.acting[1]).toBeUndefined();
    expect(next.actError[1]).toBe('4-eyes violation');
  });

  it('reject.fulfilled removes the rejected row from items', () => {
    const start = reducer(undefined, {
      type: fetchPending.fulfilled.type,
      payload: CR_LIST,
    });

    const next = reducer(start, {
      type: reject.fulfilled.type,
      meta: { arg: { id: 2, rejectedBy: 'alice', reason: 'Wrong data' } },
      payload: { id: 2, state: 'REJECTED' },
    });
    expect(next.items).toHaveLength(1);
    expect(next.items[0].id).toBe(1);
    expect(next.acting[2]).toBeUndefined();
  });

  it('clearActError removes a specific id from actError', () => {
    const withError = reducer(undefined, {
      type: approve.rejected.type,
      meta: { arg: { id: 1 } },
      payload: { id: 1, message: 'some error' },
    });
    expect(withError.actError[1]).toBe('some error');

    const cleared = reducer(withError, clearActError(1));
    expect(cleared.actError[1]).toBeUndefined();
  });

  it('fetchPending.fulfilled with non-array payload stores empty items', () => {
    const next = reducer(undefined, {
      type: fetchPending.fulfilled.type,
      payload: null,
    });
    expect(next.items).toEqual([]);
  });
});

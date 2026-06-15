/**
 * Contract lock for reconExceptionsSlice (UC-04-03, BS-04).
 *
 * Covers:
 *  - listExceptions.fulfilled : stores items array with exact BFF field names.
 *  - resolveException.pending/fulfilled/rejected : per-row acting map + in-place update.
 *  - reRunException.pending/fulfilled/rejected : per-row acting map + in-place update.
 *  - clearActError : removes a specific id from actError.
 *  - Money fields are strings — no Number() coercion.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  listExceptions,
  resolveException,
  reRunException,
  clearActError,
} from '@/store/reconExceptionsSlice';

const OPEN_EXCEPTION = {
  id: 1,
  batchId: 'BATCH-2026-06-15-001',
  merchantId: 'MID0000000000000001',
  gmeAmount: '1500000',
  schemeAmount: '1490000',
  discrepancyAmount: '10000',
  matchStatus: 'DISCREPANCY',
  exceptionStatus: 'OPEN',
  operatorId: null,
  resolutionNote: null,
  resolutionAction: null,
  resolvedAt: null,
  createdAt: '2026-06-15T01:05:00Z',
};

const MISSING_EXCEPTION = {
  id: 2,
  batchId: 'BATCH-2026-06-15-001',
  merchantId: 'MID0000000000000002',
  gmeAmount: '750000',
  schemeAmount: null,
  discrepancyAmount: '750000',
  matchStatus: 'MISSING_SCHEME',
  exceptionStatus: 'OPEN',
  operatorId: null,
  resolutionNote: null,
  resolutionAction: null,
  resolvedAt: null,
  createdAt: '2026-06-15T01:05:02Z',
};

const RESOLVED_EXCEPTION = {
  ...OPEN_EXCEPTION,
  exceptionStatus: 'RESOLVED',
  operatorId: 'alice',
  resolutionNote: 'Confirmed correct via bank statement',
  resolutionAction: 'MANUAL_OVERRIDE',
  resolvedAt: '2026-06-15T03:30:00Z',
};

describe('reconExceptionsSlice', () => {
  // --------------------------------------------------------- listExceptions
  it('listExceptions.fulfilled stores the array and clears loading/error', () => {
    const next = reducer(undefined, {
      type: listExceptions.fulfilled.type,
      payload: [OPEN_EXCEPTION, MISSING_EXCEPTION],
    });
    expect(next.items).toHaveLength(2);
    expect(next.loading).toBe(false);
    expect(next.error).toBeNull();
  });

  it('listExceptions.fulfilled with non-array payload stores empty items', () => {
    const next = reducer(undefined, {
      type: listExceptions.fulfilled.type,
      payload: null,
    });
    expect(next.items).toEqual([]);
  });

  it('listExceptions.pending sets loading=true and records filters', () => {
    const filters = { exceptionStatus: 'OPEN', matchStatus: 'DISCREPANCY' };
    const next = reducer(undefined, {
      type: listExceptions.pending.type,
      meta: { arg: filters },
    });
    expect(next.loading).toBe(true);
    expect(next.filters).toEqual(filters);
  });

  it('listExceptions.rejected stores error message', () => {
    const next = reducer(undefined, {
      type: listExceptions.rejected.type,
      error: { message: 'Network error' },
    });
    expect(next.loading).toBe(false);
    expect(next.error).toBe('Network error');
  });

  // -------------------------------------------------------- field-name contract
  it('stores BFF field names exactly — money values remain strings', () => {
    const next = reducer(undefined, {
      type: listExceptions.fulfilled.type,
      payload: [OPEN_EXCEPTION],
    });
    const row = next.items[0];
    expect(row.id).toBe(1);
    expect(row.batchId).toBe('BATCH-2026-06-15-001');
    expect(row.merchantId).toBe('MID0000000000000001');
    // Money fields are BigDecimal strings — must remain strings, not numbers.
    expect(typeof row.gmeAmount).toBe('string');
    expect(row.gmeAmount).toBe('1500000');
    expect(typeof row.schemeAmount).toBe('string');
    expect(row.schemeAmount).toBe('1490000');
    expect(typeof row.discrepancyAmount).toBe('string');
    expect(row.discrepancyAmount).toBe('10000');
    expect(row.matchStatus).toBe('DISCREPANCY');
    expect(row.exceptionStatus).toBe('OPEN');
    expect(row.createdAt).toBe('2026-06-15T01:05:00Z');
  });

  it('stores null schemeAmount correctly for MISSING_SCHEME rows', () => {
    const next = reducer(undefined, {
      type: listExceptions.fulfilled.type,
      payload: [MISSING_EXCEPTION],
    });
    const row = next.items[0];
    expect(row.schemeAmount).toBeNull();
    expect(row.matchStatus).toBe('MISSING_SCHEME');
  });

  // ---------------------------------------------------- resolveException
  it('resolveException.pending sets acting[id]="resolving"', () => {
    const next = reducer(undefined, {
      type: resolveException.pending.type,
      meta: {
        arg: { id: 1, operatorId: 'alice', note: 'ok', resolutionAction: 'MANUAL_OVERRIDE' },
      },
    });
    expect(next.acting[1]).toBe('resolving');
  });

  it('resolveException.fulfilled updates the row in-place', () => {
    const start = reducer(undefined, {
      type: listExceptions.fulfilled.type,
      payload: [OPEN_EXCEPTION, MISSING_EXCEPTION],
    });
    const next = reducer(start, {
      type: resolveException.fulfilled.type,
      meta: {
        arg: { id: 1, operatorId: 'alice', note: 'ok', resolutionAction: 'MANUAL_OVERRIDE' },
      },
      payload: RESOLVED_EXCEPTION,
    });
    // Row 0 updated in-place; row 1 unchanged.
    expect(next.items[0].exceptionStatus).toBe('RESOLVED');
    expect(next.items[0].operatorId).toBe('alice');
    expect(next.items[1].id).toBe(2);
    // acting cleared.
    expect(next.acting[1]).toBeUndefined();
  });

  it('resolveException.rejected stores actError and clears acting', () => {
    const withPending = reducer(undefined, {
      type: resolveException.pending.type,
      meta: { arg: { id: 1, operatorId: 'alice', note: '', resolutionAction: '' } },
    });
    const next = reducer(withPending, {
      type: resolveException.rejected.type,
      meta: { arg: { id: 1 } },
      payload: { id: 1, message: 'already resolved' },
    });
    expect(next.acting[1]).toBeUndefined();
    expect(next.actError[1]).toBe('already resolved');
  });

  // ------------------------------------------------------- reRunException
  it('reRunException.pending sets acting[id]="rerunning"', () => {
    const next = reducer(undefined, {
      type: reRunException.pending.type,
      meta: { arg: { id: 2, operatorId: 'bob' } },
    });
    expect(next.acting[2]).toBe('rerunning');
  });

  it('reRunException.fulfilled updates the row in-place', () => {
    const reRunRow = { ...MISSING_EXCEPTION, exceptionStatus: 'RE_RUN' };
    const start = reducer(undefined, {
      type: listExceptions.fulfilled.type,
      payload: [OPEN_EXCEPTION, MISSING_EXCEPTION],
    });
    const next = reducer(start, {
      type: reRunException.fulfilled.type,
      meta: { arg: { id: 2, operatorId: 'bob' } },
      payload: reRunRow,
    });
    expect(next.items[1].exceptionStatus).toBe('RE_RUN');
    expect(next.acting[2]).toBeUndefined();
  });

  it('reRunException.rejected stores actError', () => {
    const withPending = reducer(undefined, {
      type: reRunException.pending.type,
      meta: { arg: { id: 2, operatorId: 'bob' } },
    });
    const next = reducer(withPending, {
      type: reRunException.rejected.type,
      meta: { arg: { id: 2 } },
      payload: { id: 2, message: 'service unavailable' },
    });
    expect(next.acting[2]).toBeUndefined();
    expect(next.actError[2]).toBe('service unavailable');
  });

  // ------------------------------------------------------- clearActError
  it('clearActError removes a specific id from actError', () => {
    const withError = reducer(undefined, {
      type: resolveException.rejected.type,
      meta: { arg: { id: 1 } },
      payload: { id: 1, message: 'some error' },
    });
    expect(withError.actError[1]).toBe('some error');
    const cleared = reducer(withError, clearActError(1));
    expect(cleared.actError[1]).toBeUndefined();
  });
});

import { describe, it, expect } from 'vitest';
import reducer, {
  fetchTransactions,
  fetchTransactionDetail,
  clearDetail
} from '../transactionsSlice';

/**
 * Contract lock: the transactions reducer stores the BFF wire payload
 * unchanged.
 *
 * List wire: Array<TransactionSummary>
 *   { txnId, partnerId, state, amount, currency, committedAt }
 *
 * Detail wire: TransactionDetail
 *   { summary, schemeTxnRef, schemeApprovalCode, prefundDeductedUsd,
 *     approvedAt, bookedSettlementAmount, settlementRoundingMode,
 *     roundingResidual }
 */
describe('transactionsSlice', () => {
  it('initial state has empty list + idle detail', () => {
    const s = reducer(undefined, { type: '@@INIT' });
    expect(s.list).toEqual({ items: [], status: 'idle', error: null });
    expect(s.detail).toEqual({
      data: null,
      status: 'idle',
      error: null,
      failureStatus: null
    });
  });

  it('stores the BFF list payload verbatim', () => {
    const wire = [
      {
        txnId: 'TXN-1001',
        partnerId: 'GMEREMIT',
        state: 'COMMITTED',
        amount: '125.50',
        currency: 'USD',
        committedAt: '2026-06-09T10:15:30Z'
      },
      {
        txnId: 'TXN-1003',
        partnerId: 'GMEREMIT',
        state: 'COMMITTED',
        amount: '50000',
        currency: 'KRW',
        committedAt: '2026-06-09T12:45:00Z'
      }
    ];
    const state = reducer(undefined, {
      type: fetchTransactions.fulfilled.type,
      payload: wire
    });
    expect(state.list.status).toBe('succeeded');
    expect(state.list.items).toEqual(wire);
    expect(state.list.items[0].state).toBe('COMMITTED');
    expect(state.list.items[0].committedAt).toBe('2026-06-09T10:15:30Z');
    expect(state.list.items[1].currency).toBe('KRW');
  });

  it('coerces non-array payloads to []', () => {
    const state = reducer(undefined, {
      type: fetchTransactions.fulfilled.type,
      payload: null
    });
    expect(state.list.items).toEqual([]);
  });

  it('captures list-fetch errors', () => {
    const state = reducer(undefined, {
      type: fetchTransactions.rejected.type,
      error: { message: 'down' }
    });
    expect(state.list.status).toBe('failed');
    expect(state.list.error).toBe('down');
  });

  it('stores the detail wire payload verbatim', () => {
    const detail = {
      summary: {
        txnId: 'TXN-1001',
        partnerId: 'GMEREMIT',
        state: 'COMMITTED',
        amount: '125.50',
        currency: 'USD',
        committedAt: '2026-06-09T10:15:30Z'
      },
      schemeTxnRef: 'SCH-TXN-1001',
      schemeApprovalCode: 'AP-TXN-1001',
      prefundDeductedUsd: '125.50',
      approvedAt: '2026-06-09T10:15:28Z',
      bookedSettlementAmount: '125.50',
      settlementRoundingMode: 'HALF_UP',
      roundingResidual: '0.00'
    };
    const state = reducer(undefined, {
      type: fetchTransactionDetail.fulfilled.type,
      payload: detail
    });
    expect(state.detail.status).toBe('succeeded');
    expect(state.detail.data).toEqual(detail);
    expect(state.detail.data.summary.amount).toBe('125.50');
    expect(state.detail.data.settlementRoundingMode).toBe('HALF_UP');
  });

  it('records a 404 failureStatus from rejectWithValue', () => {
    const state = reducer(undefined, {
      type: fetchTransactionDetail.rejected.type,
      payload: { status: 404, message: 'not found' },
      error: { message: 'rejected' }
    });
    expect(state.detail.status).toBe('failed');
    expect(state.detail.failureStatus).toBe(404);
    expect(state.detail.error).toBe('not found');
  });

  it('clearDetail wipes only the detail half', () => {
    const seeded = {
      list: { items: [{ txnId: 'X' }], status: 'succeeded', error: null },
      detail: {
        data: { summary: { txnId: 'X' } },
        status: 'succeeded',
        error: null,
        failureStatus: null
      }
    };
    const after = reducer(seeded, clearDetail());
    expect(after.detail).toEqual({
      data: null,
      status: 'idle',
      error: null,
      failureStatus: null
    });
    expect(after.list.items).toHaveLength(1);
  });
});

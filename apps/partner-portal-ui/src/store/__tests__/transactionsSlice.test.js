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
 *   { txnId, partnerId, state, amount, currency, committedAt,
 *     // UC-10-02 additive (null until BFF wires them):
 *     qrSchemeId, krwAmount, payerCurrency, payerCurrencyAmount,
 *     appliedFxRate, rateTimestamp, prefundingDeductedUsd }
 *
 * Detail wire: TransactionDetail
 *   { summary, schemeTxnRef, schemeApprovalCode, prefundDeductedUsd,
 *     approvedAt, bookedSettlementAmount, settlementRoundingMode,
 *     roundingResidual,
 *     // UC-10-03 additive (null until BFF wires them):
 *     merchantId, merchantName, statusHistory }
 *
 * Money MUST stay as decimal strings — never Number-cast.
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

  it('stores the BFF list payload verbatim (original fields)', () => {
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

  it('stores UC-10-02 enriched list payload verbatim (money fields as strings)', () => {
    const wire = [
      {
        txnId: 'TXN-UC10-A',
        partnerId: 'GMEREMIT',
        state: 'APPROVED',
        amount: '125.50',
        currency: 'USD',
        committedAt: '2026-06-10T08:00:00Z',
        qrSchemeId: 'ZEROPAY',
        krwAmount: '165000',
        payerCurrency: 'USD',
        payerCurrencyAmount: '125.50',
        appliedFxRate: '1315.00',
        rateTimestamp: '2026-06-10T07:59:55Z',
        prefundingDeductedUsd: '125.50'
      }
    ];
    const state = reducer(undefined, {
      type: fetchTransactions.fulfilled.type,
      payload: wire
    });
    expect(state.list.status).toBe('succeeded');
    const item = state.list.items[0];
    expect(item.qrSchemeId).toBe('ZEROPAY');
    // Money MUST stay as strings — assert type
    expect(typeof item.krwAmount).toBe('string');
    expect(item.krwAmount).toBe('165000');
    expect(typeof item.appliedFxRate).toBe('string');
    expect(item.appliedFxRate).toBe('1315.00');
    expect(typeof item.prefundingDeductedUsd).toBe('string');
    expect(item.prefundingDeductedUsd).toBe('125.50');
    expect(item.rateTimestamp).toBe('2026-06-10T07:59:55Z');
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

  it('stores the original detail wire payload verbatim', () => {
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

  it('stores UC-10-03 enriched detail payload verbatim', () => {
    const detail = {
      summary: {
        txnId: 'TXN-UC10-001',
        partnerId: 'GMEREMIT',
        state: 'APPROVED',
        amount: '125.50',
        currency: 'USD',
        committedAt: '2026-06-10T08:00:00Z',
        qrSchemeId: 'ZEROPAY',
        krwAmount: '165000',
        payerCurrency: 'USD',
        payerCurrencyAmount: '125.50',
        appliedFxRate: '1315.00',
        rateTimestamp: '2026-06-10T07:59:55Z',
        prefundingDeductedUsd: '125.50'
      },
      schemeTxnRef: 'SCH-UC10-001',
      schemeApprovalCode: 'AP-UC10-001',
      prefundDeductedUsd: '125.50',
      approvedAt: '2026-06-10T08:00:01Z',
      bookedSettlementAmount: '125.50',
      settlementRoundingMode: 'HALF_UP',
      roundingResidual: '0.00',
      merchantId: 'MERCH-0042',
      merchantName: 'Seoul Coffee Co.',
      statusHistory: [
        { status: 'PENDING', at: '2026-06-10T07:59:50Z' },
        { status: 'APPROVED', at: '2026-06-10T08:00:01Z' }
      ]
    };
    const state = reducer(undefined, {
      type: fetchTransactionDetail.fulfilled.type,
      payload: detail
    });
    expect(state.detail.status).toBe('succeeded');
    const d = state.detail.data;
    expect(d.merchantId).toBe('MERCH-0042');
    expect(d.merchantName).toBe('Seoul Coffee Co.');
    expect(Array.isArray(d.statusHistory)).toBe(true);
    expect(d.statusHistory).toHaveLength(2);
    expect(d.statusHistory[0].status).toBe('PENDING');
    expect(d.statusHistory[1].status).toBe('APPROVED');
    // Money fields preserved as strings
    expect(typeof d.summary.krwAmount).toBe('string');
    expect(d.summary.krwAmount).toBe('165000');
    expect(typeof d.summary.appliedFxRate).toBe('string');
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

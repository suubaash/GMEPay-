import { describe, it, expect } from 'vitest';
import reducer, { fetchOverview, resetOverview } from '../overviewSlice';

/**
 * Contract lock: the overview reducer must persist the BFF wire shape
 * unchanged. If the BFF DTO drifts, this test breaks before the page does.
 *
 * Wire shape (PartnerOverview):
 *   { partnerId, balance: BalanceView, recentTxnCount, lastSettlementDate }
 *   BalanceView: { partnerId, currency, balance, lowBalanceThreshold }
 */
describe('overviewSlice', () => {
  it('starts idle with no data', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state).toEqual({ data: null, status: 'idle', error: null });
  });

  it('marks loading when the thunk starts', () => {
    const state = reducer(undefined, { type: fetchOverview.pending.type });
    expect(state.status).toBe('loading');
    expect(state.error).toBeNull();
  });

  it('stores the BFF wire payload verbatim on success', () => {
    const wire = {
      partnerId: 'GMEREMIT',
      balance: {
        partnerId: 'GMEREMIT',
        currency: 'USD',
        balance: '12500.75',
        lowBalanceThreshold: '1000.00'
      },
      recentTxnCount: 17,
      lastSettlementDate: '2026-06-08'
    };
    const state = reducer(undefined, { type: fetchOverview.fulfilled.type, payload: wire });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);
    expect(state.data.balance.balance).toBe('12500.75');
    expect(state.data.balance.lowBalanceThreshold).toBe('1000.00');
    expect(state.data.recentTxnCount).toBe(17);
    expect(state.data.lastSettlementDate).toBe('2026-06-08');
  });

  it('captures the error on rejection', () => {
    const state = reducer(undefined, {
      type: fetchOverview.rejected.type,
      error: { message: 'boom' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('boom');
  });

  it('resetOverview returns to initial state', () => {
    const dirty = {
      data: { partnerId: 'X', balance: null, recentTxnCount: 0, lastSettlementDate: null },
      status: 'succeeded',
      error: null
    };
    expect(reducer(dirty, resetOverview())).toEqual({
      data: null,
      status: 'idle',
      error: null
    });
  });
});

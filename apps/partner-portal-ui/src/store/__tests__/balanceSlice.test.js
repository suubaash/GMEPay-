import { describe, it, expect } from 'vitest';
import reducer, { fetchBalance } from '../balanceSlice';

/**
 * Contract lock: the balance reducer must persist the BFF BalanceView wire
 * shape verbatim.
 *
 * UC-10-01 wire fields:
 *   { partnerCode, currency, balance, threshold, pctOfThreshold, recentDeductions }
 *
 * Legacy BFF wire also accepted:
 *   { partnerId, currency, balance, lowBalanceThreshold }
 *
 * Money MUST stay as decimal strings — balance, threshold, pctOfThreshold,
 * and recentDeductions[].amountUsd must never be Number-cast.
 */
describe('balanceSlice', () => {
  it('starts idle', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state).toEqual({ data: null, status: 'idle', error: null });
  });

  it('stores the legacy BFF BalanceView verbatim (backward compat)', () => {
    const wire = {
      partnerId: 'GMEREMIT',
      currency: 'USD',
      balance: '12500.75',
      lowBalanceThreshold: '1000.00'
    };
    const state = reducer(undefined, { type: fetchBalance.fulfilled.type, payload: wire });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);
    // The two money fields MUST stay as decimal strings (BigDecimal precision).
    expect(state.data.balance).toBe('12500.75');
    expect(state.data.lowBalanceThreshold).toBe('1000.00');
  });

  it('stores the UC-10-01 BalanceView verbatim (enriched shape)', () => {
    const wire = {
      partnerCode: 'GMEREMIT',
      currency: 'USD',
      balance: '9875.50',
      threshold: '1000.00',
      pctOfThreshold: '987.55',
      recentDeductions: [
        { amountUsd: '100.00', at: '2026-06-15T01:00:00Z', txnRef: 'TXN-D001' },
        { amountUsd: '24.50',  at: '2026-06-14T10:00:00Z', txnRef: 'TXN-D002' }
      ]
    };
    const state = reducer(undefined, { type: fetchBalance.fulfilled.type, payload: wire });
    expect(state.status).toBe('succeeded');
    expect(state.data).toEqual(wire);

    // Money fields MUST stay as decimal strings
    expect(typeof state.data.balance).toBe('string');
    expect(state.data.balance).toBe('9875.50');
    expect(typeof state.data.threshold).toBe('string');
    expect(state.data.threshold).toBe('1000.00');
    expect(typeof state.data.pctOfThreshold).toBe('string');
    expect(state.data.pctOfThreshold).toBe('987.55');

    // recentDeductions preserved
    expect(Array.isArray(state.data.recentDeductions)).toBe(true);
    expect(state.data.recentDeductions).toHaveLength(2);
    expect(state.data.recentDeductions[0].txnRef).toBe('TXN-D001');
    // amountUsd must stay as string
    expect(typeof state.data.recentDeductions[0].amountUsd).toBe('string');
    expect(state.data.recentDeductions[0].amountUsd).toBe('100.00');
    expect(state.data.recentDeductions[1].amountUsd).toBe('24.50');
  });

  it('stores null recentDeductions when not present in wire', () => {
    const wire = {
      partnerCode: 'GMEREMIT',
      currency: 'USD',
      balance: '5000.00',
      threshold: '1000.00',
      pctOfThreshold: '500.00',
      recentDeductions: null
    };
    const state = reducer(undefined, { type: fetchBalance.fulfilled.type, payload: wire });
    expect(state.data.recentDeductions).toBeNull();
  });

  it('captures the error on rejection', () => {
    const state = reducer(undefined, {
      type: fetchBalance.rejected.type,
      error: { message: 'boom' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('boom');
  });
});

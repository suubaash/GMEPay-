import { describe, it, expect } from 'vitest';
import reducer, { fetchBalance } from '../balanceSlice';

/**
 * Contract lock: the balance reducer must persist the BFF BalanceView wire
 * shape verbatim. Wire fields: { partnerId, currency, balance, lowBalanceThreshold }.
 */
describe('balanceSlice', () => {
  it('starts idle', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state).toEqual({ data: null, status: 'idle', error: null });
  });

  it('stores the BFF BalanceView verbatim', () => {
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

  it('captures the error on rejection', () => {
    const state = reducer(undefined, {
      type: fetchBalance.rejected.type,
      error: { message: 'boom' }
    });
    expect(state.status).toBe('failed');
    expect(state.error).toBe('boom');
  });
});

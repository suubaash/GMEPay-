/**
 * Unit tests for txnSearchSlice.
 *
 * Covers:
 *  1. Initial state shape.
 *  2. fetchTxnSearch.pending sets loading + echoes filters.
 *  3. fetchTxnSearch.fulfilled stores content, page, size, totalElements.
 *  4. fetchTxnSearch.rejected sets error.
 *  5. exportTxnCsv.pending / fulfilled / rejected update csvLoading + csvError.
 *  6. setFilters reducer.
 *  7. clearError reducer.
 *  8. resetSearch reducer.
 *  9. Money values arrive as strings (BigDecimal) — never cast to Number.
 * 10. totalElements field name (not `total`) matches the TxnSearch wire contract.
 */
import { describe, expect, it } from 'vitest';
import reducer, {
  fetchTxnSearch,
  exportTxnCsv,
  setFilters,
  clearError,
  resetSearch,
} from '@/store/txnSearchSlice';

// ---- fixtures ----

const ROW_1 = {
  txnRef: 'TXN-2024-0001',
  partnerRef: 'GME_KR_001',
  sendAmount: '100000',       // BigDecimal-as-string
  sendCcy: 'KRW',
  targetPayout: '75.50',      // BigDecimal-as-string
  targetCcy: 'USD',
  status: 'SETTLED',
  createdAt: '2024-06-15T09:30:00+09:00',
  qrSchemeId: 'ZEROPAY',
  krwAmount: '100000',        // BigDecimal-as-string
  payerCurrency: 'KRW',
  payerCurrencyAmount: '100000',
  appliedFxRate: '1324.50',   // BigDecimal-as-string
  prefundingDeductedUsd: '75.50',
  merchantName: 'Seoul Mart',
};

const PAGE_RESPONSE = {
  content: [ROW_1],
  page: 0,
  size: 20,
  totalElements: 1,
};

// ---- tests ----

describe('txnSearchSlice', () => {
  it('has correct initial state', () => {
    const state = reducer(undefined, { type: '@@INIT' });
    expect(state.items).toEqual([]);
    expect(state.page).toBe(0);
    expect(state.size).toBe(20);
    expect(state.totalElements).toBe(0);
    expect(state.loading).toBe(false);
    expect(state.csvLoading).toBe(false);
    expect(state.error).toBeNull();
    expect(state.csvError).toBeNull();
    expect(state.filters).toEqual({});
  });

  // 2. pending sets loading + echoes filters
  it('fetchTxnSearch.pending sets loading=true and echoes filters', () => {
    const params = { txnRef: 'TXN-001', status: 'SETTLED', page: 0, size: 20 };
    const state = reducer(undefined, {
      type: fetchTxnSearch.pending.type,
      meta: { arg: params },
    });
    expect(state.loading).toBe(true);
    expect(state.error).toBeNull();
    expect(state.filters).toEqual(params);
  });

  // 3. fulfilled stores content
  it('fetchTxnSearch.fulfilled stores items + pagination using totalElements', () => {
    const state = reducer(undefined, {
      type: fetchTxnSearch.fulfilled.type,
      payload: PAGE_RESPONSE,
    });
    expect(state.loading).toBe(false);
    expect(state.items).toHaveLength(1);
    expect(state.items[0].txnRef).toBe('TXN-2024-0001');
    expect(state.page).toBe(0);
    expect(state.size).toBe(20);
    // Wire field is totalElements (not total — different from transactionsSlice)
    expect(state.totalElements).toBe(1);
  });

  // 9 & 10. Money as strings
  it('money fields remain as BigDecimal strings — never cast to Number', () => {
    const state = reducer(undefined, {
      type: fetchTxnSearch.fulfilled.type,
      payload: PAGE_RESPONSE,
    });
    const row = state.items[0];
    // All money fields must be strings, not numbers
    expect(typeof row.sendAmount).toBe('string');
    expect(typeof row.targetPayout).toBe('string');
    expect(typeof row.krwAmount).toBe('string');
    expect(typeof row.appliedFxRate).toBe('string');
    expect(typeof row.prefundingDeductedUsd).toBe('string');
    expect(typeof row.payerCurrencyAmount).toBe('string');
    // Verify they are NOT Number-cast (e.g. '100000' not 100000)
    expect(row.krwAmount).toBe('100000');
    expect(row.appliedFxRate).toBe('1324.50');
  });

  // 4. rejected sets error
  it('fetchTxnSearch.rejected sets error message', () => {
    const state = reducer(undefined, {
      type: fetchTxnSearch.rejected.type,
      error: { message: 'Backend unavailable' },
    });
    expect(state.loading).toBe(false);
    expect(state.error).toBe('Backend unavailable');
    expect(state.items).toEqual([]);
  });

  // 5a. exportTxnCsv.pending
  it('exportTxnCsv.pending sets csvLoading=true', () => {
    const state = reducer(undefined, { type: exportTxnCsv.pending.type });
    expect(state.csvLoading).toBe(true);
    expect(state.csvError).toBeNull();
  });

  // 5b. exportTxnCsv.fulfilled
  it('exportTxnCsv.fulfilled clears csvLoading', () => {
    const base = reducer(undefined, { type: exportTxnCsv.pending.type });
    const state = reducer(base, {
      type: exportTxnCsv.fulfilled.type,
      payload: 'txnRef\nTXN-001',
    });
    expect(state.csvLoading).toBe(false);
    expect(state.csvError).toBeNull();
  });

  // 5c. exportTxnCsv.rejected
  it('exportTxnCsv.rejected sets csvError from payload', () => {
    const base = reducer(undefined, { type: exportTxnCsv.pending.type });
    const state = reducer(base, {
      type: exportTxnCsv.rejected.type,
      payload: 'CSV export failed — server error',
    });
    expect(state.csvLoading).toBe(false);
    expect(state.csvError).toBe('CSV export failed — server error');
  });

  // 6. setFilters
  it('setFilters replaces filter state', () => {
    const state = reducer(undefined, setFilters({ txnRef: 'X', status: 'FAILED' }));
    expect(state.filters).toEqual({ txnRef: 'X', status: 'FAILED' });
  });

  // 7. clearError
  it('clearError nulls the error field', () => {
    const errState = reducer(undefined, {
      type: fetchTxnSearch.rejected.type,
      error: { message: 'oops' },
    });
    expect(errState.error).toBe('oops');
    const cleared = reducer(errState, clearError());
    expect(cleared.error).toBeNull();
  });

  // 8. resetSearch
  it('resetSearch restores initial state', () => {
    // Load some data first
    let state = reducer(undefined, {
      type: fetchTxnSearch.fulfilled.type,
      payload: PAGE_RESPONSE,
    });
    expect(state.items).toHaveLength(1);
    state = reducer(state, resetSearch());
    expect(state.items).toEqual([]);
    expect(state.totalElements).toBe(0);
    expect(state.filters).toEqual({});
    expect(state.error).toBeNull();
    expect(state.loading).toBe(false);
  });

  // Defensive: empty content array
  it('handles empty content array gracefully', () => {
    const state = reducer(undefined, {
      type: fetchTxnSearch.fulfilled.type,
      payload: { content: [], page: 2, size: 50, totalElements: 0 },
    });
    expect(state.items).toEqual([]);
    expect(state.page).toBe(2);
    expect(state.size).toBe(50);
    expect(state.totalElements).toBe(0);
  });

  // Defensive: null payload
  it('handles null payload without throwing', () => {
    const state = reducer(undefined, {
      type: fetchTxnSearch.fulfilled.type,
      payload: null,
    });
    expect(state.items).toEqual([]);
    expect(state.totalElements).toBe(0);
  });
});

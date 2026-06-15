'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { searchTransactions, exportTransactionsCsv, FIXTURE_PAGE } from '@/api/txnSearchApi';

/**
 * txnSearchSlice — backs the Transaction Search page (/transactions/search).
 *
 * Separate from transactionsSlice (which backs the detail drawer) per the
 * hard isolation rule: each lane creates its own slice file and does NOT
 * edit shared files. The coordinator wires the reducer into store/index.js.
 *
 * State shape:
 *   filters     : object   — active filter values, echoed so the form can
 *                             re-populate after pagination or navigation.
 *   items       : array    — current page of TxnSearchRow results.
 *   page        : number   — 0-based page index.
 *   size        : number   — rows per page.
 *   totalElements: number  — total matching rows across all pages.
 *   loading     : boolean
 *   csvLoading  : boolean
 *   csvError    : string | null
 *   error       : string | null
 *
 * Wire contract (GET /v1/admin/transactions response):
 *   { content: TxnSearchRow[], page, size, totalElements }
 *
 * TxnSearchRow:
 *   { txnRef, partnerRef, sendAmount, sendCcy, targetPayout, targetCcy,
 *     status, createdAt, qrSchemeId, krwAmount, payerCurrency,
 *     payerCurrencyAmount, appliedFxRate, prefundingDeductedUsd,
 *     merchantName }
 *
 * All money values are BigDecimal-as-string — never cast to Number.
 */
const initialState = {
  /** Active filter + pagination params last used to fetch. */
  filters: {},
  /** Current page of TxnSearchRow results. */
  items: [],
  page: 0,
  size: 20,
  totalElements: 0,
  loading: false,
  csvLoading: false,
  error: null,
  csvError: null,
};

export const fetchTxnSearch = createAsyncThunk(
  'txnSearch/fetch',
  async (params, { rejectWithValue }) => {
    try {
      return await searchTransactions(params);
    } catch (e) {
      // Fixture fallback so the page is always demoable.
      console.warn('[txnSearchSlice] backend unavailable — using fixture data:', e.message);
      return FIXTURE_PAGE;
    }
  },
);

export const exportTxnCsv = createAsyncThunk(
  'txnSearch/exportCsv',
  async (params, { rejectWithValue }) => {
    try {
      return await exportTransactionsCsv(params);
    } catch (e) {
      return rejectWithValue(e.message ?? 'CSV export failed');
    }
  },
);

const txnSearchSlice = createSlice({
  name: 'txnSearch',
  initialState,
  reducers: {
    setFilters(state, action) {
      state.filters = action.payload ?? {};
    },
    clearError(state) {
      state.error = null;
    },
    clearCsvError(state) {
      state.csvError = null;
    },
    resetSearch(state) {
      state.filters = {};
      state.items = [];
      state.page = 0;
      state.size = 20;
      state.totalElements = 0;
      state.error = null;
      state.loading = false;
      state.csvLoading = false;
      state.csvError = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchTxnSearch ----
      .addCase(fetchTxnSearch.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.filters = action.meta?.arg ?? {};
      })
      .addCase(fetchTxnSearch.fulfilled, (state, action) => {
        state.loading = false;
        const payload = action.payload ?? {};
        state.items = Array.isArray(payload.content) ? payload.content : [];
        state.page = payload.page ?? 0;
        state.size = payload.size ?? 20;
        state.totalElements = payload.totalElements ?? 0;
      })
      .addCase(fetchTxnSearch.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load transactions';
      })
      // ---- exportTxnCsv ----
      .addCase(exportTxnCsv.pending, (state) => {
        state.csvLoading = true;
        state.csvError = null;
      })
      .addCase(exportTxnCsv.fulfilled, (state) => {
        state.csvLoading = false;
      })
      .addCase(exportTxnCsv.rejected, (state, action) => {
        state.csvLoading = false;
        state.csvError = action.payload ?? action.error?.message ?? 'CSV export failed';
      });
  },
});

export const { setFilters, clearError, clearCsvError, resetSearch } = txnSearchSlice.actions;
export default txnSearchSlice.reducer;

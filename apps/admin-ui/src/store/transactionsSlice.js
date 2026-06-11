'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Transactions slice — feeds the search page + detail drawer.
 *
 * Search response (Spring Page) -> { content, page, size, total }
 * TransactionSummary fields used by the UI:
 *   { txnId, partnerId, state, amount (decimal string), currency, committedAt }
 *
 * Detail response: TransactionDetail
 *   { summary: TransactionSummary,
 *     schemeTxnRef, schemeApprovalCode,
 *     prefundDeductedUsd, approvedAt,
 *     bookedSettlementAmount, settlementRoundingMode, roundingResidual }
 */
const initialState = {
  /** Current page of search results — array of TransactionSummary. */
  items: [],
  /** Pagination metadata mirroring the BFF's Page<T>. */
  page: 0,
  size: 20,
  /** Total matching rows (BFF field is `total`, NOT totalElements). */
  total: 0,
  /** Active filters echoed back so the form can re-populate on navigation. */
  filters: {},
  /** Detail cache keyed by txnId — values are TransactionDetail. */
  details: {},
  loading: false,
  detailLoading: false,
  error: null,
};

export const searchTransactions = createAsyncThunk(
  'transactions/search',
  async (filters) => {
    return adminApi.searchTransactions(filters);
  },
);

export const getTransaction = createAsyncThunk(
  'transactions/get',
  async (id) => {
    return adminApi.getTransaction(id);
  },
);

export const fetchRecentTransactions = createAsyncThunk(
  'transactions/fetchRecent',
  async () => {
    return adminApi.listRecentTxns();
  },
);

const transactionsSlice = createSlice({
  name: 'transactions',
  initialState,
  reducers: {
    setFilters(state, action) {
      state.filters = action.payload ?? {};
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(searchTransactions.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.filters = action.meta?.arg ?? {};
      })
      .addCase(searchTransactions.fulfilled, (state, action) => {
        state.loading = false;
        const page = action.payload ?? {};
        state.items = Array.isArray(page.content) ? page.content : [];
        state.page = page.page ?? 0;
        state.size = page.size ?? 20;
        state.total = page.total ?? 0;
      })
      .addCase(searchTransactions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load transactions';
      })
      .addCase(getTransaction.pending, (state) => {
        state.detailLoading = true;
        state.error = null;
      })
      .addCase(getTransaction.fulfilled, (state, action) => {
        state.detailLoading = false;
        const detail = action.payload;
        const id = detail?.summary?.txnId;
        if (id) {
          state.details[id] = detail;
        }
      })
      .addCase(getTransaction.rejected, (state, action) => {
        state.detailLoading = false;
        state.error = action.error?.message ?? 'Failed to load transaction';
      })
      .addCase(fetchRecentTransactions.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchRecentTransactions.fulfilled, (state, action) => {
        state.loading = false;
        const list = Array.isArray(action.payload) ? action.payload : [];
        state.items = list;
        state.total = list.length;
      })
      .addCase(fetchRecentTransactions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load transactions';
      });
  },
});

export const { setFilters } = transactionsSlice.actions;
export default transactionsSlice.reducer;

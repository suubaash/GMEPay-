'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type {
  Page,
  RecentTxn,
  TransactionDetail,
  TransactionSearchFilters,
} from '@/api/types';

export interface TransactionsState {
  /** Current page of search results. */
  items: RecentTxn[];
  /** Pagination metadata mirroring the BFF's Spring Page<T>. */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  /** Active filters (echoed back so the form can re-populate on navigation). */
  filters: TransactionSearchFilters;
  /** Detail cache keyed by transaction id. */
  details: Record<string, TransactionDetail>;
  loading: boolean;
  detailLoading: boolean;
  error: string | null;
}

const initialState: TransactionsState = {
  items: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
  filters: {},
  details: {},
  loading: false,
  detailLoading: false,
  error: null,
};

export const searchTransactions = createAsyncThunk(
  'transactions/search',
  async (filters: TransactionSearchFilters) => {
    return adminApi.searchTransactions(filters);
  },
);

export const getTransaction = createAsyncThunk(
  'transactions/get',
  async (id: string) => {
    return adminApi.getTransaction(id);
  },
);

/**
 * Backwards-compatible alias kept so the dashboard skeleton's `fetchRecentTransactions`
 * thunk still works. New code should use searchTransactions with explicit filters.
 */
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
    setFilters(state, action: PayloadAction<TransactionSearchFilters>) {
      state.filters = action.payload;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(searchTransactions.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.filters = action.meta.arg;
      })
      .addCase(
        searchTransactions.fulfilled,
        (state, action: PayloadAction<Page<RecentTxn>>) => {
          state.loading = false;
          state.items = action.payload.content;
          state.page = action.payload.page;
          state.size = action.payload.size;
          state.totalElements = action.payload.totalElements;
          state.totalPages = action.payload.totalPages;
        },
      )
      .addCase(searchTransactions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load transactions';
      })
      .addCase(getTransaction.pending, (state) => {
        state.detailLoading = true;
        state.error = null;
      })
      .addCase(
        getTransaction.fulfilled,
        (state, action: PayloadAction<TransactionDetail>) => {
          state.detailLoading = false;
          state.details[action.payload.id] = action.payload;
        },
      )
      .addCase(getTransaction.rejected, (state, action) => {
        state.detailLoading = false;
        state.error = action.error.message ?? 'Failed to load transaction';
      })
      .addCase(fetchRecentTransactions.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(
        fetchRecentTransactions.fulfilled,
        (state, action: PayloadAction<RecentTxn[]>) => {
          state.loading = false;
          state.items = action.payload;
          state.totalElements = action.payload.length;
          state.totalPages = 1;
        },
      )
      .addCase(fetchRecentTransactions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load transactions';
      });
  },
});

export const { setFilters } = transactionsSlice.actions;
export default transactionsSlice.reducer;

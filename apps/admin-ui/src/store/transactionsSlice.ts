'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type { RecentTxn } from '@/api/types';

export interface TransactionsState {
  items: RecentTxn[];
  loading: boolean;
  error: string | null;
}

const initialState: TransactionsState = {
  items: [],
  loading: false,
  error: null,
};

export const fetchRecentTransactions = createAsyncThunk(
  'transactions/fetchRecent',
  async () => {
    return adminApi.listRecentTxns();
  },
);

const transactionsSlice = createSlice({
  name: 'transactions',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchRecentTransactions.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(
        fetchRecentTransactions.fulfilled,
        (state, action: PayloadAction<RecentTxn[]>) => {
          state.loading = false;
          state.items = action.payload;
        },
      )
      .addCase(fetchRecentTransactions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load transactions';
      });
  },
});

export default transactionsSlice.reducer;

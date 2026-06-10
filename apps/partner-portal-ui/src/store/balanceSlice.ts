'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import type { BalanceDto } from '@/api/types';
import type { LoadState } from './overviewSlice';

interface BalanceState {
  data: BalanceDto | null;
  status: LoadState;
  error: string | null;
}

const initialState: BalanceState = { data: null, status: 'idle', error: null };

export const fetchBalance = createAsyncThunk(
  'balance/fetch',
  async (partnerId: string) => portalApi.getBalance(partnerId)
);

const slice = createSlice({
  name: 'balance',
  initialState,
  reducers: {
    resetBalance: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchBalance.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(fetchBalance.fulfilled, (s, a: PayloadAction<BalanceDto>) => {
        s.status = 'succeeded';
        s.data = a.payload;
      })
      .addCase(fetchBalance.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.error.message ?? 'Failed to load balance';
      });
  }
});

export const { resetBalance } = slice.actions;
export default slice.reducer;

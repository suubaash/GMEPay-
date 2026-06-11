'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Balance slice.
 *
 * Wire shape — GET /v1/portal/{partnerId}/balance returns BalanceView:
 *   {
 *     partnerId: string,
 *     currency: string,                 // ISO-4217
 *     balance: string,                  // BigDecimal-as-string in major units
 *     lowBalanceThreshold: string       // BigDecimal-as-string in major units
 *   }
 *
 * Note: there is NO `lastUpdatedAt` field on the wire — render with safe
 * defaults if a page needs to display "last updated".
 *
 * State: { data, status, error }
 */

const initialState = { data: null, status: 'idle', error: null };

export const fetchBalance = createAsyncThunk(
  'balance/fetch',
  async (partnerId) => portalApi.getBalance(partnerId)
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
      .addCase(fetchBalance.fulfilled, (s, a) => {
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

'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Overview slice.
 *
 * Wire shape — GET /v1/portal/{partnerId}/overview returns PartnerOverview:
 *   {
 *     partnerId: string,
 *     balance: BalanceView { partnerId, currency, balance, lowBalanceThreshold },
 *     recentTxnCount: number,
 *     lastSettlementDate: string | null   // LocalDate (YYYY-MM-DD)
 *   }
 *
 * Money fields inside `balance` are BigDecimal-as-string (docs/MONEY_CONVENTION.md).
 *
 * State: { data, status, error }
 *   data: PartnerOverview | null
 *   status: 'idle' | 'loading' | 'succeeded' | 'failed'
 */

const initialState = { data: null, status: 'idle', error: null };

export const fetchOverview = createAsyncThunk(
  'overview/fetch',
  async (partnerId) => portalApi.getOverview(partnerId)
);

const slice = createSlice({
  name: 'overview',
  initialState,
  reducers: {
    resetOverview: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchOverview.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(fetchOverview.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.data = a.payload;
      })
      .addCase(fetchOverview.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.error.message ?? 'Failed to load overview';
      });
  }
});

export const { resetOverview } = slice.actions;
export default slice.reducer;

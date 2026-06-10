'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type {
  DateRange,
  RevenueBreakdown,
  RevenueSummary,
} from '@/api/types';

export interface RevenueState {
  summary: RevenueSummary | null;
  breakdown: RevenueBreakdown | null;
  loading: boolean;
  breakdownLoading: boolean;
  error: string | null;
  /** The range last requested — keeps the form in sync after navigation. */
  range: DateRange | null;
}

const initialState: RevenueState = {
  summary: null,
  breakdown: null,
  loading: false,
  breakdownLoading: false,
  error: null,
  range: null,
};

export const getSummary = createAsyncThunk(
  'revenue/getSummary',
  async (range: DateRange | undefined) => {
    return adminApi.getRevenueSummary(range);
  },
);

export const getBreakdown = createAsyncThunk(
  'revenue/getBreakdown',
  async (args: (DateRange & { dimension?: 'partner' | 'scheme' | 'currency' }) | undefined) => {
    return adminApi.getRevenueBreakdown(args);
  },
);

const revenueSlice = createSlice({
  name: 'revenue',
  initialState,
  reducers: {
    setRange(state, action: PayloadAction<DateRange | null>) {
      state.range = action.payload;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(getSummary.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        if (action.meta.arg) state.range = action.meta.arg;
      })
      .addCase(
        getSummary.fulfilled,
        (state, action: PayloadAction<RevenueSummary>) => {
          state.loading = false;
          state.summary = action.payload;
        },
      )
      .addCase(getSummary.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load revenue summary';
      })
      .addCase(getBreakdown.pending, (state) => {
        state.breakdownLoading = true;
        state.error = null;
      })
      .addCase(
        getBreakdown.fulfilled,
        (state, action: PayloadAction<RevenueBreakdown>) => {
          state.breakdownLoading = false;
          state.breakdown = action.payload;
        },
      )
      .addCase(getBreakdown.rejected, (state, action) => {
        state.breakdownLoading = false;
        state.error = action.error.message ?? 'Failed to load revenue breakdown';
      });
  },
});

export const { setRange } = revenueSlice.actions;
export default revenueSlice.reducer;

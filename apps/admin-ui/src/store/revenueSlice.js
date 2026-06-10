'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Revenue slice — drives the /revenue page.
 *
 * GET /v1/admin/revenue/summary?from&to -> RevenueSummary
 *   { date, totalRevenueUsd, feeRevenueUsd, marginRevenueUsd }
 *
 * GET /v1/admin/revenue/breakdown?from&to -> RevenueBreakdown
 *   { byPartner: { string -> string },
 *     byScheme:  { string -> string },
 *     byCurrency:{ string -> string } }
 * Map values are decimal strings (USD totals).
 *
 * `range` mirrors the requested { from, to } so the form survives navigation.
 */
const initialState = {
  summary: null,
  breakdown: null,
  loading: false,
  breakdownLoading: false,
  error: null,
  range: null,
};

export const getSummary = createAsyncThunk(
  'revenue/getSummary',
  async (range) => {
    return adminApi.getRevenueSummary(range);
  },
);

export const getBreakdown = createAsyncThunk(
  'revenue/getBreakdown',
  async (range) => {
    return adminApi.getRevenueBreakdown(range);
  },
);

const revenueSlice = createSlice({
  name: 'revenue',
  initialState,
  reducers: {
    setRange(state, action) {
      state.range = action.payload ?? null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(getSummary.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        if (action.meta?.arg) state.range = action.meta.arg;
      })
      .addCase(getSummary.fulfilled, (state, action) => {
        state.loading = false;
        state.summary = action.payload ?? null;
      })
      .addCase(getSummary.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load revenue summary';
      })
      .addCase(getBreakdown.pending, (state) => {
        state.breakdownLoading = true;
        state.error = null;
      })
      .addCase(getBreakdown.fulfilled, (state, action) => {
        state.breakdownLoading = false;
        state.breakdown = action.payload ?? null;
      })
      .addCase(getBreakdown.rejected, (state, action) => {
        state.breakdownLoading = false;
        state.error = action.error?.message ?? 'Failed to load revenue breakdown';
      });
  },
});

export const { setRange } = revenueSlice.actions;
export default revenueSlice.reducer;

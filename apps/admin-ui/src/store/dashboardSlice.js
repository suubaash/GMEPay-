'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Dashboard slice — holds the AdminDashboard payload from GET /v1/admin/dashboard.
 *
 * BFF shape stored verbatim:
 *   { recentTxnCount:int, partnerCount:int, lowBalanceCount:int, todayRevenueUsd:number }
 */
const initialState = {
  data: null,
  loading: false,
  error: null,
};

export const fetchDashboard = createAsyncThunk('dashboard/fetch', async () => {
  return adminApi.fetchDashboard();
});

const dashboardSlice = createSlice({
  name: 'dashboard',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchDashboard.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchDashboard.fulfilled, (state, action) => {
        state.loading = false;
        state.data = action.payload ?? null;
      })
      .addCase(fetchDashboard.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load dashboard';
      });
  },
});

export default dashboardSlice.reducer;

'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type { AdminDashboard } from '@/api/types';

export interface DashboardState {
  data: AdminDashboard | null;
  loading: boolean;
  error: string | null;
}

const initialState: DashboardState = {
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
      .addCase(
        fetchDashboard.fulfilled,
        (state, action: PayloadAction<AdminDashboard>) => {
          state.loading = false;
          state.data = action.payload;
        },
      )
      .addCase(fetchDashboard.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load dashboard';
      });
  },
});

export default dashboardSlice.reducer;

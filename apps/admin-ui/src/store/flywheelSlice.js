'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Flywheel slice — holds the FlywheelDashboard payload from
 * GET /v1/admin/flywheel (the 7 growth-loop KPIs from
 * docs/QR_HUB_GROWTH_FLYWHEEL.md §5).
 *
 * BFF shape stored verbatim:
 *   { window, network, volume, capital, loopHealth }
 * Every metric is nullable — null means "not yet measurable", never zero.
 */
const initialState = {
  data: null,
  loading: false,
  error: null,
};

export const fetchFlywheel = createAsyncThunk('flywheel/fetch', async (range) => {
  return adminApi.getFlywheel(range);
});

const flywheelSlice = createSlice({
  name: 'flywheel',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchFlywheel.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchFlywheel.fulfilled, (state, action) => {
        state.loading = false;
        state.data = action.payload ?? null;
      })
      .addCase(fetchFlywheel.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load flywheel metrics';
      });
  },
});

export default flywheelSlice.reducer;

'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * System-health slice — live BFF/service status.
 *
 * Fed by GET /v1/admin/system/health -> SystemHealth
 *   { checkedAt:ISO, services:[ServiceHealth] }
 *
 * ServiceHealth { name, status:"UP"|"DOWN"|"DEGRADED", lastSeenAt, uptimeSec }
 *
 * The page polls this every 30s; the slice tracks the latest checkedAt so
 * the UI can show "Last checked X seconds ago".
 */
const initialState = {
  services: [],
  checkedAt: null,
  loading: false,
  error: null,
};

export const fetchSystemHealth = createAsyncThunk(
  'systemHealth/fetch',
  async (_arg, { rejectWithValue }) => {
    try {
      return await adminApi.getSystemHealth();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return rejectWithValue(msg);
    }
  },
);

const systemHealthSlice = createSlice({
  name: 'systemHealth',
  initialState,
  reducers: {
    clearError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchSystemHealth.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchSystemHealth.fulfilled, (state, action) => {
        state.loading = false;
        const payload = action.payload ?? {};
        state.services = Array.isArray(payload.services) ? payload.services : [];
        state.checkedAt = payload.checkedAt ?? null;
      })
      .addCase(fetchSystemHealth.rejected, (state, action) => {
        state.loading = false;
        state.error =
          (action.payload) ??
          action.error?.message ??
          'Failed to load system health';
      });
  },
});

export const { clearError } = systemHealthSlice.actions;
export default systemHealthSlice.reducer;

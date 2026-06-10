'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import type { OverviewDto } from '@/api/types';

export type LoadState = 'idle' | 'loading' | 'succeeded' | 'failed';

interface OverviewState {
  data: OverviewDto | null;
  status: LoadState;
  error: string | null;
}

const initialState: OverviewState = { data: null, status: 'idle', error: null };

export const fetchOverview = createAsyncThunk(
  'overview/fetch',
  async (partnerId: string) => portalApi.getOverview(partnerId)
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
      .addCase(fetchOverview.fulfilled, (s, a: PayloadAction<OverviewDto>) => {
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

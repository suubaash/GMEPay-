'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import type { PartnerProfileDto } from '@/api/types';
import type { LoadState } from './overviewSlice';

interface ProfileState {
  data: PartnerProfileDto | null;
  status: LoadState;
  error: string | null;
}

const initialState: ProfileState = { data: null, status: 'idle', error: null };

export const fetchProfile = createAsyncThunk(
  'profile/fetch',
  async (partnerId: string) => portalApi.getProfile(partnerId)
);

const slice = createSlice({
  name: 'profile',
  initialState,
  reducers: {
    resetProfile: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchProfile.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(fetchProfile.fulfilled, (s, a: PayloadAction<PartnerProfileDto>) => {
        s.status = 'succeeded';
        s.data = a.payload;
      })
      .addCase(fetchProfile.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.error.message ?? 'Failed to load profile';
      });
  }
});

export const { resetProfile } = slice.actions;
export default slice.reducer;

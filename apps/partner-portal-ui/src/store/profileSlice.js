'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * Profile slice.
 *
 * Wire shape — GET /v1/portal/{partnerId}/profile returns PartnerProfile:
 *   { partnerId, type, settlementCurrency, settlementRoundingMode, onboardedAt }
 *
 * Note: NO `displayName` on the wire — the page falls back to `partnerId`.
 */

const initialState = { data: null, status: 'idle', error: null };

export const fetchProfile = createAsyncThunk(
  'profile/fetch',
  async (partnerId) => portalApi.getProfile(partnerId)
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
      .addCase(fetchProfile.fulfilled, (s, a) => {
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

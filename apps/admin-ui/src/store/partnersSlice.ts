'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type { PartnerCreateRequest, PartnerSummary } from '@/api/types';

export interface PartnersState {
  items: PartnerSummary[];
  loading: boolean;
  error: string | null;
}

const initialState: PartnersState = {
  items: [],
  loading: false,
  error: null,
};

export const fetchPartners = createAsyncThunk('partners/fetch', async () => {
  return adminApi.listPartners();
});

export const createPartner = createAsyncThunk(
  'partners/create',
  async (req: PartnerCreateRequest) => {
    return adminApi.createPartner(req);
  },
);

const partnersSlice = createSlice({
  name: 'partners',
  initialState,
  reducers: {
    clearError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchPartners.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(
        fetchPartners.fulfilled,
        (state, action: PayloadAction<PartnerSummary[]>) => {
          state.loading = false;
          state.items = action.payload;
        },
      )
      .addCase(fetchPartners.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? 'Failed to load partners';
      })
      .addCase(createPartner.fulfilled, (state, action) => {
        state.items = [...state.items, action.payload];
      })
      .addCase(createPartner.rejected, (state, action) => {
        state.error = action.error.message ?? 'Failed to create partner';
      });
  },
});

export const { clearError } = partnersSlice.actions;
export default partnersSlice.reducer;

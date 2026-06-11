'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Partners slice — fed by /v1/admin/partners (config-registry via the BFF).
 *
 * Each item is a PartnerSummary:
 *   { partnerId:string, type:string, settlementCurrency:string, settlementRoundingMode:string }
 *
 * `details` caches the same shape by partnerId.
 */
const initialState = {
  items: [],
  details: {},
  loading: false,
  error: null,
  detailLoading: false,
  saving: false,
};

export const fetchPartners = createAsyncThunk('partners/fetch', async () => {
  return adminApi.listPartners();
});

export const getPartner = createAsyncThunk('partners/get', async (id) => {
  return adminApi.getPartner(id);
});

export const createPartner = createAsyncThunk(
  'partners/create',
  async (req) => {
    return adminApi.createPartner(req);
  },
);

export const updatePartnerRoundingMode = createAsyncThunk(
  'partners/updateRoundingMode',
  async (args) => {
    return adminApi.updateRoundingMode(args.id, args.mode);
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
      .addCase(fetchPartners.fulfilled, (state, action) => {
        state.loading = false;
        state.items = action.payload ?? [];
      })
      .addCase(fetchPartners.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load partners';
      })
      .addCase(getPartner.pending, (state) => {
        state.detailLoading = true;
        state.error = null;
      })
      .addCase(getPartner.fulfilled, (state, action) => {
        state.detailLoading = false;
        const p = action.payload;
        if (p && p.partnerId) {
          state.details[p.partnerId] = p;
        }
      })
      .addCase(getPartner.rejected, (state, action) => {
        state.detailLoading = false;
        state.error = action.error?.message ?? 'Failed to load partner';
      })
      .addCase(createPartner.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(createPartner.fulfilled, (state, action) => {
        state.saving = false;
        const p = action.payload;
        if (p && p.partnerId) {
          state.items = [...state.items, p];
          state.details[p.partnerId] = p;
        }
      })
      .addCase(createPartner.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to create partner';
      })
      .addCase(updatePartnerRoundingMode.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(updatePartnerRoundingMode.fulfilled, (state, action) => {
        state.saving = false;
        const updated = action.payload;
        if (!updated || !updated.partnerId) return;
        state.details[updated.partnerId] = updated;
        state.items = state.items.map((p) =>
          p.partnerId === updated.partnerId
            ? { ...p, settlementRoundingMode: updated.settlementRoundingMode }
            : p,
        );
      })
      .addCase(updatePartnerRoundingMode.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to update rounding mode';
      });
  },
});

export const { clearError } = partnersSlice.actions;
export default partnersSlice.reducer;

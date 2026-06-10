'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import type { PartnerCreateRequest, PartnerDetail, PartnerSummary, RoundingMode } from '@/api/types';

export interface PartnersState {
  items: PartnerSummary[];
  /** Detail cache keyed by partnerId. Hydrated by getPartner thunk. */
  details: Record<string, PartnerDetail>;
  loading: boolean;
  error: string | null;
  /** Independent flag for the partner-detail route so list loads don't toggle it. */
  detailLoading: boolean;
  /** Independent flag for create/update mutations. */
  saving: boolean;
}

const initialState: PartnersState = {
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

export const getPartner = createAsyncThunk(
  'partners/get',
  async (id: string) => {
    return adminApi.getPartner(id);
  },
);

export const createPartner = createAsyncThunk(
  'partners/create',
  async (req: PartnerCreateRequest) => {
    return adminApi.createPartner(req);
  },
);

export const updatePartnerRoundingMode = createAsyncThunk(
  'partners/updateRoundingMode',
  async (args: { id: string; mode: RoundingMode }) => {
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
      .addCase(getPartner.pending, (state) => {
        state.detailLoading = true;
        state.error = null;
      })
      .addCase(
        getPartner.fulfilled,
        (state, action: PayloadAction<PartnerDetail>) => {
          state.detailLoading = false;
          state.details[action.payload.partnerId] = action.payload;
        },
      )
      .addCase(getPartner.rejected, (state, action) => {
        state.detailLoading = false;
        state.error = action.error.message ?? 'Failed to load partner';
      })
      .addCase(createPartner.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(createPartner.fulfilled, (state, action) => {
        state.saving = false;
        state.items = [...state.items, action.payload];
        state.details[action.payload.partnerId] = action.payload;
      })
      .addCase(createPartner.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error.message ?? 'Failed to create partner';
      })
      .addCase(updatePartnerRoundingMode.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(updatePartnerRoundingMode.fulfilled, (state, action) => {
        state.saving = false;
        const updated = action.payload;
        state.details[updated.partnerId] = updated;
        state.items = state.items.map((p) =>
          p.partnerId === updated.partnerId
            ? { ...p, settlementRoundingMode: updated.settlementRoundingMode }
            : p,
        );
      })
      .addCase(updatePartnerRoundingMode.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error.message ?? 'Failed to update rounding mode';
      });
  },
});

export const { clearError } = partnersSlice.actions;
export default partnersSlice.reducer;

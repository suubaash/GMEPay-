'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Scheme-side commission-share slice — backs the QR Schemes page commission
 * editor (V031, configurable GME↔scheme split of the net merchant fee; there
 * is no fixed 70/30).
 *
 * State:
 *   - `byScheme`       : map schemeId → SchemeCommissionShareView[]
 *   - `loadingByScheme`: map schemeId → boolean
 *   - `saving`         : boolean — PUT in-flight
 *   - `error`          : last user-visible failure
 *
 * SchemeCommissionShareView (GET /v1/admin/schemes/{schemeId}/commission-shares):
 *   { id, schemeId, direction|null, gmeSharePct, vanFeePct (decimal strings),
 *     validFrom, validTo|null, recordedAt }
 *
 * The PUT body carries the FULL desired set (bulk replace); empty clears.
 */
const initialState = {
  byScheme: {},
  loadingByScheme: {},
  saving: false,
  error: null,
};

/** GET /v1/admin/schemes/{schemeId}/commission-shares -> view[] */
export const fetchSchemeCommissionShares = createAsyncThunk(
  'schemeCommission/fetch',
  async (schemeId) => {
    const shares = await adminApi.getSchemeCommissionShares(schemeId);
    return { schemeId, shares };
  },
);

/**
 * PUT /v1/admin/schemes/{schemeId}/commission-shares (bulk replace).
 *
 * @param {object} params
 * @param {string} params.schemeId
 * @param {Array}  params.shares  Full desired set (SchemeCommissionShareCommand[]).
 */
export const saveSchemeCommissionShares = createAsyncThunk(
  'schemeCommission/save',
  async ({ schemeId, shares }) => {
    const updated = await adminApi.saveSchemeCommissionShares(schemeId, shares);
    return { schemeId, shares: updated };
  },
);

const schemeCommissionSlice = createSlice({
  name: 'schemeCommission',
  initialState,
  reducers: {
    clearSchemeCommissionError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchSchemeCommissionShares.pending, (state, action) => {
        state.loadingByScheme[action.meta.arg] = true;
        state.error = null;
      })
      .addCase(fetchSchemeCommissionShares.fulfilled, (state, action) => {
        const { schemeId, shares } = action.payload;
        state.loadingByScheme[schemeId] = false;
        state.byScheme[schemeId] = Array.isArray(shares) ? shares : [];
      })
      .addCase(fetchSchemeCommissionShares.rejected, (state, action) => {
        state.loadingByScheme[action.meta.arg] = false;
        state.error = action.error?.message ?? 'Failed to load scheme commission shares';
      })
      .addCase(saveSchemeCommissionShares.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(saveSchemeCommissionShares.fulfilled, (state, action) => {
        state.saving = false;
        const { schemeId, shares } = action.payload;
        state.byScheme[schemeId] = Array.isArray(shares) ? shares : [];
      })
      .addCase(saveSchemeCommissionShares.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save scheme commission shares';
      });
  },
});

export const { clearSchemeCommissionError } = schemeCommissionSlice.actions;
export default schemeCommissionSlice.reducer;

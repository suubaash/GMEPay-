'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Partner-side commission-share slice — backs the Step 6 Commission Sharing
 * section (V031, configurable GME↔partner split of GME's commission; there is
 * no fixed 70/30).
 *
 * State:
 *   - `sharesByCode`   : map partnerCode → PartnerCommissionShareView[]
 *   - `loadingByCode`  : map partnerCode → boolean
 *   - `saving`         : boolean — PUT in-flight
 *   - `error`          : last user-visible failure
 *
 * PartnerCommissionShareView (GET /v1/admin/partners/{code}/commission-shares):
 *   { id, schemeId|null, direction|null, partnerSharePct (decimal fraction string),
 *     validFrom, validTo|null, recordedAt }
 *
 * The PUT body carries the FULL desired set (bulk replace). An empty array
 * clears all rows. Shares are always decimal STRINGS per docs/MONEY_CONVENTION.md.
 */
const initialState = {
  sharesByCode: {},
  loadingByCode: {},
  saving: false,
  error: null,
};

/** GET /v1/admin/partners/{partnerCode}/commission-shares -> view[] */
export const fetchPartnerCommissionShares = createAsyncThunk(
  'commissionShares/fetch',
  async (partnerCode) => {
    const shares = await adminApi.getPartnerCommissionShares(partnerCode);
    return { partnerCode, shares };
  },
);

/**
 * PUT /v1/admin/partners/{partnerCode}/commission-shares (bulk replace).
 *
 * @param {object} params
 * @param {string} params.partnerCode
 * @param {Array}  params.shares  Full desired set (PartnerCommissionShareCommand[]).
 */
export const savePartnerCommissionShares = createAsyncThunk(
  'commissionShares/save',
  async ({ partnerCode, shares }) => {
    const updated = await adminApi.savePartnerCommissionShares(partnerCode, shares);
    return { partnerCode, shares: updated };
  },
);

const commissionSharesSlice = createSlice({
  name: 'commissionShares',
  initialState,
  reducers: {
    clearCommissionSharesError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchPartnerCommissionShares.pending, (state, action) => {
        state.loadingByCode[action.meta.arg] = true;
        state.error = null;
      })
      .addCase(fetchPartnerCommissionShares.fulfilled, (state, action) => {
        const { partnerCode, shares } = action.payload;
        state.loadingByCode[partnerCode] = false;
        state.sharesByCode[partnerCode] = Array.isArray(shares) ? shares : [];
      })
      .addCase(fetchPartnerCommissionShares.rejected, (state, action) => {
        state.loadingByCode[action.meta.arg] = false;
        // Non-fatal: a new draft may have no commission rows yet.
        state.error = action.error?.message ?? 'Failed to load commission shares';
      })
      .addCase(savePartnerCommissionShares.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(savePartnerCommissionShares.fulfilled, (state, action) => {
        state.saving = false;
        const { partnerCode, shares } = action.payload;
        state.sharesByCode[partnerCode] = Array.isArray(shares) ? shares : [];
      })
      .addCase(savePartnerCommissionShares.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save commission shares';
      });
  },
});

export const { clearCommissionSharesError } = commissionSharesSlice.actions;
export default commissionSharesSlice.reducer;

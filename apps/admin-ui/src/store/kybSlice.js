'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * KYB slice — backs the Step 3 KYB form in the Partner Setup wizard.
 *
 * Manages:
 *   - `kybByCode`  : map of partnerCode → KybView (cached per-partner).
 *   - `kybLoading` : loading flag for fetchKyb / runScreening.
 *   - `kybError`   : last user-visible failure from KYB operations.
 *
 * KybView shape (from GET /api/v1/admin/partners/{code}/kyb):
 *   {
 *     partnerCode, riskRating, riskRationale, nextReviewDate,
 *     licenseType, licenseNumber, licenseAuthority, licenseExpiry,
 *     uboList: [{ name, ownershipPct, isPep, country }],
 *     cbddqDocId,
 *     screeningStatus: 'CLEAR'|'NEEDS_REVIEW'|'HIT'|null,
 *     screeningProviderRef: string|null,
 *     screenedAt: ISO-8601 instant|null,
 *     screeningHits: [{ name, matchScore, matchType, source }]|null,
 *   }
 */
const initialState = {
  /** partnerCode → KybView */
  kybByCode: {},
  kybLoading: false,
  kybError: null,
};

/**
 * GET /v1/admin/partners/{code}/kyb -> KybView
 */
export const fetchKyb = createAsyncThunk(
  'kyb/fetch',
  async (partnerCode) => {
    return adminApi.getKyb(partnerCode);
  },
);

/**
 * POST /v1/admin/partners/{code}/kyb/screen -> KybView (refreshed)
 */
export const runScreening = createAsyncThunk(
  'kyb/runScreening',
  async (partnerCode) => {
    return adminApi.runKybScreening(partnerCode);
  },
);

const kybSlice = createSlice({
  name: 'kyb',
  initialState,
  reducers: {
    clearKybError(state) {
      state.kybError = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchKyb ----
      .addCase(fetchKyb.pending, (state) => {
        state.kybLoading = true;
        state.kybError = null;
      })
      .addCase(fetchKyb.fulfilled, (state, action) => {
        state.kybLoading = false;
        const view = action.payload;
        if (view && view.partnerCode) {
          state.kybByCode[view.partnerCode] = view;
        }
      })
      .addCase(fetchKyb.rejected, (state, action) => {
        state.kybLoading = false;
        state.kybError = action.error?.message ?? 'Failed to load KYB data';
      })
      // ---- runScreening ----
      .addCase(runScreening.pending, (state) => {
        state.kybLoading = true;
        state.kybError = null;
      })
      .addCase(runScreening.fulfilled, (state, action) => {
        state.kybLoading = false;
        const view = action.payload;
        if (view && view.partnerCode) {
          state.kybByCode[view.partnerCode] = view;
        }
      })
      .addCase(runScreening.rejected, (state, action) => {
        state.kybLoading = false;
        state.kybError = action.error?.message ?? 'Screening request failed';
      });
  },
});

export const { clearKybError } = kybSlice.actions;
export default kybSlice.reducer;

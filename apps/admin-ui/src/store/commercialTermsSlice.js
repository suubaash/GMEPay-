'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Commercial-terms slice — backs the Step 6 Commercial sections in the
 * Partner Setup wizard (Slice 6, ADR-010 bitemporal SCD-6).
 *
 * Manages:
 *   - `configByCode`  : map partnerCode → CommercialTermsView
 *   - `loadingByCode` : map partnerCode → boolean
 *   - `saving`        : boolean — PATCH in-flight
 *   - `error`         : last user-visible failure
 *
 * CommercialTermsView (GET /v1/admin/partners/draft/{code}/commercial):
 *   {
 *     feeSchedule: { scheme, direction, fixedFeeUsd, bpsFee, tiers:[...] },
 *     fxConfig:    { marginBps, referenceRateSource, quoteHoldSeconds },
 *     limits:      { perTxnMinUsd, perTxnMaxUsd, dailyCapUsd, monthlyCapUsd,
 *                    annualCapUsd, licenseType },
 *     contract:    { effectiveFrom, effectiveTo, autoRenewal, noticePeriodDays,
 *                    refundChargebackPolicy, terminationReason }
 *   }
 *
 * The PATCH that persists the step goes through patchDraftStep6Commercial
 * defined in this slice (separate from the generic patchStep6 thunk in
 * draftsSlice which routes through adminApi.patchDraftStep).
 */
const initialState = {
  /** partnerCode → CommercialTermsView */
  configByCode: {},
  /** partnerCode → boolean */
  loadingByCode: {},
  saving: false,
  error: null,
};

/**
 * GET /v1/admin/partners/draft/{partnerCode}/commercial
 * -> CommercialTermsView
 */
export const fetchCommercial = createAsyncThunk(
  'commercialTerms/fetch',
  async (partnerCode) => {
    const config = await adminApi.getCommercialTerms(partnerCode);
    return { partnerCode, config };
  },
);

/**
 * PATCH /v1/admin/partners/draft/{partnerCode}/step-6-commercial
 * body: { feeSchedule, fxConfig, limits, contract }
 * -> PartnerView (refreshed with updated bitemporal stamps)
 */
export const patchDraftStep6Commercial = createAsyncThunk(
  'commercialTerms/patch',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep6Commercial(partnerCode, body);
  },
);

const commercialTermsSlice = createSlice({
  name: 'commercialTerms',
  initialState,
  reducers: {
    clearCommercialError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchCommercial ----
      .addCase(fetchCommercial.pending, (state, action) => {
        const code = action.meta.arg;
        state.loadingByCode[code] = true;
        state.error = null;
      })
      .addCase(fetchCommercial.fulfilled, (state, action) => {
        const { partnerCode, config } = action.payload;
        state.loadingByCode[partnerCode] = false;
        if (config) {
          state.configByCode[partnerCode] = config;
        }
      })
      .addCase(fetchCommercial.rejected, (state, action) => {
        const code = action.meta.arg;
        state.loadingByCode[code] = false;
        // Non-fatal: new drafts have no commercial config yet.
        state.error =
          action.error?.message ?? 'Failed to load commercial terms';
      })
      // ---- patchDraftStep6Commercial ----
      .addCase(patchDraftStep6Commercial.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(patchDraftStep6Commercial.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(patchDraftStep6Commercial.rejected, (state, action) => {
        state.saving = false;
        state.error =
          action.error?.message ?? 'Failed to save commercial terms';
      });
  },
});

export const { clearCommercialError } = commercialTermsSlice.actions;
export default commercialTermsSlice.reducer;

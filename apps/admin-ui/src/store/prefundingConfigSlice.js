'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Prefunding-config slice — backs the Step 5 Prefunding form in the Partner
 * Setup wizard (Slice 5, ADR-010 bitemporal).
 *
 * Only applicable to OVERSEAS partners; the form enforces that guard.
 *
 * Manages:
 *   - `configByCode`  : map partnerCode → PrefundingConfigView
 *   - `loadingByCode` : map partnerCode → boolean
 *   - `error`         : last user-visible failure
 *
 * PrefundingConfigView (GET /v1/admin/partners/draft/{code}/prefunding-config):
 *   {
 *     fundingModel:            'PREFUNDED' | 'POSTPAID' | 'HYBRID',
 *     openingBalanceUsd:       string (decimal),
 *     lowBalanceThresholdUsd:  string (decimal),
 *     alertTier70:             boolean,
 *     alertTier85:             boolean,
 *     alertTier95:             boolean,
 *     creditLimitUsd:          string (decimal),
 *     autoSuspendOnBreach:     boolean,
 *     floatTopUpBankAccountId: string | null,
 *     topUpReferencePattern:   string,
 *     collateralAmountUsd:     string (decimal),
 *   }
 *
 * The PATCH that persists the step goes through the existing `patchStep5`
 * thunk in draftsSlice (adminApi.patchDraftStep(5, ...)).
 * This slice only handles the read path (GET).
 */
const initialState = {
  /** partnerCode → PrefundingConfigView */
  configByCode: {},
  /** partnerCode → boolean */
  loadingByCode: {},
  error: null,
};

/**
 * GET /v1/admin/partners/draft/{partnerCode}/prefunding-config
 * -> PrefundingConfigView
 */
export const fetchPrefundingConfig = createAsyncThunk(
  'prefundingConfig/fetch',
  async (partnerCode) => {
    const config = await adminApi.getPrefundingConfig(partnerCode);
    return { partnerCode, config };
  },
);

const prefundingConfigSlice = createSlice({
  name: 'prefundingConfig',
  initialState,
  reducers: {
    clearPrefundingConfigError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchPrefundingConfig.pending, (state, action) => {
        const code = action.meta.arg;
        state.loadingByCode[code] = true;
        state.error = null;
      })
      .addCase(fetchPrefundingConfig.fulfilled, (state, action) => {
        const { partnerCode, config } = action.payload;
        state.loadingByCode[partnerCode] = false;
        if (config) {
          state.configByCode[partnerCode] = config;
        }
      })
      .addCase(fetchPrefundingConfig.rejected, (state, action) => {
        const code = action.meta.arg;
        state.loadingByCode[code] = false;
        // Non-fatal: new drafts have no prefunding config yet.
        state.error = action.error?.message ?? 'Failed to load prefunding config';
      });
  },
});

export const { clearPrefundingConfigError } = prefundingConfigSlice.actions;
export default prefundingConfigSlice.reducer;

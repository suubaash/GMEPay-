'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Settlement-config slice — backs the Step 4 Settlement Panel in the Partner
 * Setup wizard (Slice 4B, ADR-010 bitemporal SCD-6).
 *
 * NOTE: do NOT confuse with `settlementSlice` which manages settlement *batches*
 * (GET /v1/admin/settlement/recent). This slice is named `settlementConfig` to
 * avoid colliding with the batches slice already registered in store/index.js.
 *
 * Manages:
 *   - `configByCode`   : map partnerCode → SettlementConfigView
 *   - `previewByCode`  : map partnerCode → SettlementPreviewView
 *   - `configLoading`  : map partnerCode → boolean
 *   - `previewLoading` : map partnerCode → boolean
 *   - `patchSaving`    : boolean — PATCH in-flight
 *   - `error`          : last user-visible failure
 *
 * SettlementConfigView (GET /v1/admin/partners/draft/{code}/settlement-config):
 *   { cycleTPlusN: 0..5, cutoffTime: "HH:mm", cutoffTimezone: string,
 *     settlementMethod: 'SWIFT'|'ACH'|'FPS'|'RTGS'|'SEPA'|'CHAPS'|'OTHER' }
 *
 * SettlementPreviewView (GET /v1/admin/partners/draft/{code}/settlement-preview?txnInstant=ISO):
 *   { payoutDate: "YYYY-MM-DD", explanation: string[] }
 *
 * NOTE (Slice 8 deferred): The 2-authorized-signatory approval flow for
 * POST-ACTIVATION settlement-config changes is deferred to Slice 8 (FSM).
 * During onboarding drafts, writes go direct (audited). This slice does not
 * implement the approval gate.
 */
const initialState = {
  /** partnerCode → SettlementConfigView */
  configByCode: {},
  /** partnerCode → SettlementPreviewView */
  previewByCode: {},
  /** partnerCode → boolean */
  configLoading: {},
  /** partnerCode → boolean */
  previewLoading: {},
  /** boolean — PATCH /step-4-settlement in-flight */
  patchSaving: false,
  error: null,
};

/**
 * GET /v1/admin/partners/draft/{partnerCode}/settlement-config
 */
export const fetchSettlementConfig = createAsyncThunk(
  'settlementConfig/fetch',
  async (partnerCode) => {
    const config = await adminApi.getSettlementConfig(partnerCode);
    return { partnerCode, config };
  },
);

/**
 * GET /v1/admin/partners/draft/{partnerCode}/settlement-preview?txnInstant=ISO
 */
export const fetchSettlementPreview = createAsyncThunk(
  'settlementConfig/preview',
  async ({ partnerCode, txnInstant }) => {
    const preview = await adminApi.getSettlementPreview(partnerCode, txnInstant);
    return { partnerCode, preview };
  },
);

/**
 * PATCH /v1/admin/partners/draft/{partnerCode}/step-4-settlement
 * body: { cycleTPlusN, cutoffTime, cutoffTimezone, settlementMethod }
 */
export const patchDraftStep4Settlement = createAsyncThunk(
  'settlementConfig/patch',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep4Settlement(partnerCode, body);
  },
);

const settlementConfigSlice = createSlice({
  name: 'settlementConfig',
  initialState,
  reducers: {
    clearSettlementConfigError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchSettlementConfig ----
      .addCase(fetchSettlementConfig.pending, (state, action) => {
        const code = action.meta.arg;
        state.configLoading[code] = true;
        state.error = null;
      })
      .addCase(fetchSettlementConfig.fulfilled, (state, action) => {
        const { partnerCode, config } = action.payload;
        state.configLoading[partnerCode] = false;
        if (config) {
          state.configByCode[partnerCode] = config;
        }
      })
      .addCase(fetchSettlementConfig.rejected, (state, action) => {
        const code = action.meta.arg;
        state.configLoading[code] = false;
        // Non-fatal: new drafts have no config yet — panel starts with defaults.
        state.error = action.error?.message ?? 'Failed to load settlement config';
      })

      // ---- fetchSettlementPreview ----
      .addCase(fetchSettlementPreview.pending, (state, action) => {
        const { partnerCode } = action.meta.arg;
        state.previewLoading[partnerCode] = true;
      })
      .addCase(fetchSettlementPreview.fulfilled, (state, action) => {
        const { partnerCode, preview } = action.payload;
        state.previewLoading[partnerCode] = false;
        if (preview) {
          state.previewByCode[partnerCode] = preview;
        }
      })
      .addCase(fetchSettlementPreview.rejected, (state, action) => {
        const { partnerCode } = action.meta.arg;
        state.previewLoading[partnerCode] = false;
        // Non-fatal: preview is best-effort UI chrome.
      })

      // ---- patchDraftStep4Settlement ----
      .addCase(patchDraftStep4Settlement.pending, (state) => {
        state.patchSaving = true;
        state.error = null;
      })
      .addCase(patchDraftStep4Settlement.fulfilled, (state) => {
        state.patchSaving = false;
      })
      .addCase(patchDraftStep4Settlement.rejected, (state, action) => {
        state.patchSaving = false;
        state.error = action.error?.message ?? 'Failed to save settlement config';
      });
  },
});

export const { clearSettlementConfigError } = settlementConfigSlice.actions;
export default settlementConfigSlice.reducer;

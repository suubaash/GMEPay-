'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * KYB slice — backs the Step 3 KYB form in the Partner Setup wizard.
 *
 * Manages:
 *   - `kybByCode`         : map of partnerCode → KybView (cached per-partner).
 *   - `provenanceByCode`  : map of partnerCode → KybScreeningProvenance (GAP T1-4).
 *   - `kybLoading`        : loading flag for fetchKyb / runScreening.
 *   - `attesting`         : loading flag for recordManualAttestation, kept SEPARATE from
 *                           `kybLoading` so recording an attestation does not disable the
 *                           screening controls and vice versa.
 *   - `kybError`          : last user-visible failure from KYB operations.
 *
 * KybView shape (from GET /api/v1/admin/partners/{code}/kyb):
 *   {
 *     riskRating, riskRationale, nextReviewDate,
 *     licenseType, licenseNumber, licenseAuthority, licenseExpiry,
 *     uboList: [{ name, ownershipPct, isPep, country }],
 *     cbddqDocId,
 *     screeningStatus: 'CLEAR'|'CLEAR_MANUAL_ATTESTATION'|'NEEDS_REVIEW'|'HIT'
 *                      |'NOT_SCREENED_NO_PROVIDER'|null,
 *     screeningProviderRef: string|null,
 *     screenedAt: ISO-8601 instant|null,
 *     screeningHits: [{ name, matchScore, matchType, source }]|null,
 *   }
 *
 * NOTE on keying: `KybView` carries no `partnerCode` field (it is a child view of the partner
 * aggregate — see `libs/lib-api-contracts/KybView`). Every reducer here therefore keys off
 * `action.meta.arg`, the partner code the thunk was dispatched with, falling back to a
 * `partnerCode` on the payload only for stubs/fixtures that supply one.
 */
const initialState = {
  /** partnerCode → KybView */
  kybByCode: {},
  /** partnerCode → KybScreeningProvenance (GAP T1-4: which authority produced the verdict) */
  provenanceByCode: {},
  kybLoading: false,
  attesting: false,
  kybError: null,
};

/** The partner code a KYB thunk was dispatched with. */
function codeOf(action) {
  const arg = action.meta?.arg;
  if (typeof arg === 'string' && arg) return arg;
  if (arg && typeof arg.partnerCode === 'string' && arg.partnerCode) return arg.partnerCode;
  return action.payload?.partnerCode ?? null;
}

function storeView(state, action) {
  const code = codeOf(action);
  const view = action.payload;
  if (code && view) state.kybByCode[code] = view;
}

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

/**
 * GET /v1/admin/partners/{code}/kyb/screening-provenance -> KybScreeningProvenance
 *
 * Absence is not an error the operator needs to see: a partner with no KYB row yet 404s, and the
 * caller renders the status chip regardless. So a failure clears the cached provenance rather than
 * setting `kybError` — but it clears it rather than leaving a stale one, because a stale provenance
 * would attribute the CURRENT verdict to a previous authority.
 */
export const fetchScreeningProvenance = createAsyncThunk(
  'kyb/fetchScreeningProvenance',
  async (partnerCode) => {
    return adminApi.getKybScreeningProvenance(partnerCode);
  },
);

/**
 * POST /v1/admin/partners/{code}/kyb/manual-screening-attestation -> KybView (refreshed)
 *
 * GAP T1-4: records a named human's attestation that they performed the sanctions/PEP screening
 * by hand under a compliance-signed SOP.
 *
 * @param {{ partnerCode: string, outcome: string, sopDocumentRef: string, sopVersion: string,
 *           sourcesConsulted: string, attestation: string }} arg
 */
export const recordManualAttestation = createAsyncThunk(
  'kyb/recordManualAttestation',
  async ({ partnerCode, ...body }) => {
    return adminApi.recordKybManualAttestation(partnerCode, body);
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
        storeView(state, action);
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
        storeView(state, action);
      })
      .addCase(runScreening.rejected, (state, action) => {
        state.kybLoading = false;
        state.kybError = action.error?.message ?? 'Screening request failed';
      })
      // ---- fetchScreeningProvenance ----
      .addCase(fetchScreeningProvenance.fulfilled, (state, action) => {
        const code = codeOf(action);
        if (code) state.provenanceByCode[code] = action.payload ?? null;
      })
      .addCase(fetchScreeningProvenance.rejected, (state, action) => {
        const code = codeOf(action);
        if (code) delete state.provenanceByCode[code];
      })
      // ---- recordManualAttestation (GAP T1-4) ----
      .addCase(recordManualAttestation.pending, (state) => {
        state.attesting = true;
        state.kybError = null;
      })
      .addCase(recordManualAttestation.fulfilled, (state, action) => {
        state.attesting = false;
        storeView(state, action);
        // The provenance the UI holds describes the PREVIOUS authority; drop it so the caller's
        // refetch is the only source. Keeping it would show the old attester next to a new verdict.
        const code = codeOf(action);
        if (code) delete state.provenanceByCode[code];
      })
      .addCase(recordManualAttestation.rejected, (state, action) => {
        state.attesting = false;
        state.kybError = action.error?.message ?? 'Recording the attestation failed';
      });
  },
});

export const { clearKybError } = kybSlice.actions;
export default kybSlice.reducer;

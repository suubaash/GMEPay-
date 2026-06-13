'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Partner-schemes slice — backs Step 7 (Schemes & Corridors) in the Partner
 * Setup wizard (Slice 7).
 *
 * Manages per-partner scheme enrollments and corridor definitions:
 *
 *   schemesByCode  : partnerCode → PartnerSchemeView[]
 *   corridorsByCode: partnerCode → PartnerCorridorView[]
 *   loadingByCode  : partnerCode → boolean
 *   saving         : boolean — PATCH in-flight
 *   error          : last user-visible failure
 *
 * PartnerSchemeView (GET /v1/admin/partners/draft/{code}/step-7/schemes):
 *   {
 *     schemeId:              string,
 *     enabled:               boolean,
 *     direction:             'INBOUND'|'OUTBOUND'|'BOTH',
 *     role:                  'ACQUIRER'|'ISSUER'|'BOTH',
 *     zeropayMerchantId:     string|null,
 *     zeropaySubMerchantId:  string|null,
 *     kftcInstitutionCode:   string|null,
 *     partnerTypeChar:       'D'|'I'|null,
 *     approvalMethodCpm:     'CONFIRMATION'|'SILENT'|null,
 *     approvalMethodMpm:     'CONFIRMATION'|'SILENT'|null,
 *   }
 *
 * PartnerCorridorView (GET /v1/admin/partners/draft/{code}/step-7/corridors):
 *   {
 *     id:          string,
 *     srcCountry:  string,
 *     srcCcy:      string,
 *     dstCountry:  string,
 *     dstCcy:      string,
 *     goLiveDate:  string (YYYY-MM-DD),
 *     active:      boolean,
 *   }
 */
const initialState = {
  /** partnerCode → PartnerSchemeView[] */
  schemesByCode: {},
  /** partnerCode → PartnerCorridorView[] */
  corridorsByCode: {},
  /** partnerCode → boolean */
  loadingByCode: {},
  saving: false,
  error: null,
};

/**
 * GET /v1/admin/partners/draft/{partnerCode}/step-7/schemes
 * -> PartnerSchemeView[]
 */
export const fetchPartnerSchemes = createAsyncThunk(
  'partnerSchemes/fetchSchemes',
  async (partnerCode) => {
    const schemes = await adminApi.listPartnerSchemes(partnerCode);
    return { partnerCode, schemes };
  },
);

/**
 * GET /v1/admin/partners/draft/{partnerCode}/step-7/corridors
 * -> PartnerCorridorView[]
 */
export const fetchPartnerCorridors = createAsyncThunk(
  'partnerSchemes/fetchCorridors',
  async (partnerCode) => {
    const corridors = await adminApi.listPartnerCorridors(partnerCode);
    return { partnerCode, corridors };
  },
);

/**
 * PATCH /v1/admin/partners/draft/{partnerCode}/step-7-schemes
 * body: { schemes: PartnerSchemeCommand[] }
 * -> PartnerView (refreshed bitemporal stamps)
 */
export const updateStep7Schemes = createAsyncThunk(
  'partnerSchemes/patchSchemes',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep7Schemes(partnerCode, body);
  },
);

/**
 * PATCH /v1/admin/partners/draft/{partnerCode}/step-7-corridors
 * body: { corridors: PartnerCorridorCommand[] }
 * -> PartnerView (refreshed bitemporal stamps)
 */
export const updateStep7Corridors = createAsyncThunk(
  'partnerSchemes/patchCorridors',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep7Corridors(partnerCode, body);
  },
);

const partnerSchemesSlice = createSlice({
  name: 'partnerSchemes',
  initialState,
  reducers: {
    clearPartnerSchemesError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchPartnerSchemes ----
      .addCase(fetchPartnerSchemes.pending, (state, action) => {
        state.loadingByCode[action.meta.arg] = true;
        state.error = null;
      })
      .addCase(fetchPartnerSchemes.fulfilled, (state, action) => {
        const { partnerCode, schemes } = action.payload;
        state.loadingByCode[partnerCode] = false;
        state.schemesByCode[partnerCode] = Array.isArray(schemes) ? schemes : [];
      })
      .addCase(fetchPartnerSchemes.rejected, (state, action) => {
        state.loadingByCode[action.meta.arg] = false;
        state.error = action.error?.message ?? 'Failed to load partner schemes';
      })

      // ---- fetchPartnerCorridors ----
      .addCase(fetchPartnerCorridors.pending, (state, action) => {
        state.loadingByCode[action.meta.arg] = true;
        state.error = null;
      })
      .addCase(fetchPartnerCorridors.fulfilled, (state, action) => {
        const { partnerCode, corridors } = action.payload;
        state.loadingByCode[partnerCode] = false;
        state.corridorsByCode[partnerCode] = Array.isArray(corridors) ? corridors : [];
      })
      .addCase(fetchPartnerCorridors.rejected, (state, action) => {
        state.loadingByCode[action.meta.arg] = false;
        state.error = action.error?.message ?? 'Failed to load partner corridors';
      })

      // ---- updateStep7Schemes ----
      .addCase(updateStep7Schemes.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(updateStep7Schemes.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(updateStep7Schemes.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save scheme enrollments';
      })

      // ---- updateStep7Corridors ----
      .addCase(updateStep7Corridors.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(updateStep7Corridors.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(updateStep7Corridors.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save corridors';
      });
  },
});

export const { clearPartnerSchemesError } = partnerSchemesSlice.actions;
export default partnerSchemesSlice.reducer;

'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Partners slice — fed by /v1/admin/partners + /v1/admin/partners/drafts
 * (config-registry via the BFF).
 *
 * Active rows (items) are PartnerSummary:
 *   { partnerId, type, settlementCurrency, settlementRoundingMode }
 *
 * Drafts are PartnerView (Slice 1, ADR-012 server-side persistence):
 *   { id, partnerCode, type, settlementCurrency, settlementRoundingMode,
 *     legalNameLocal, legalNameRomanized, taxId, taxIdType,
 *     countryOfIncorporation, legalForm, registeredAddress, operatingAddress,
 *     lei, status, validFrom, validTo, recordedAt }
 *
 * `details` caches the active-partner shape by partnerId.
 */
const initialState = {
  items: [],
  drafts: [],
  details: {},
  loading: false,
  draftsLoading: false,
  error: null,
  draftsError: null,
  detailLoading: false,
  saving: false,
  creatingDraft: false,
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

/**
 * GET /v1/admin/partners/drafts — Slice 1 (ADR-012). Operators see one row per
 * partner currently mid-wizard. Used by the `/partners` Drafts table; the
 * "Resume" button on each row routes to /partners/draft/{partnerCode}/step-1.
 */
export const fetchDrafts = createAsyncThunk('partners/fetchDrafts', async () => {
  return adminApi.listPartnerDrafts();
});

/**
 * POST /v1/admin/partners/draft — creates a new empty draft and returns the
 * canonical PartnerView. The "New partner" button on /partners dispatches this
 * to mint a partnerCode, then routes to /partners/draft/{partnerCode}/step-1.
 *
 * The thunk accepts an optional partial body so future callers can seed a
 * `partnerCode`; when called with no argument the BFF generates one.
 */
export const createDraft = createAsyncThunk(
  'partners/createDraft',
  async (body) => {
    return adminApi.createPartnerDraft(body ?? {});
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
      })
      .addCase(fetchDrafts.pending, (state) => {
        state.draftsLoading = true;
        state.draftsError = null;
      })
      .addCase(fetchDrafts.fulfilled, (state, action) => {
        state.draftsLoading = false;
        state.drafts = action.payload ?? [];
      })
      .addCase(fetchDrafts.rejected, (state, action) => {
        state.draftsLoading = false;
        state.draftsError = action.error?.message ?? 'Failed to load drafts';
      })
      .addCase(createDraft.pending, (state) => {
        state.creatingDraft = true;
        state.error = null;
      })
      .addCase(createDraft.fulfilled, (state, action) => {
        state.creatingDraft = false;
        const d = action.payload;
        if (d && d.partnerCode) {
          // Prepend so the freshly created draft shows up at the top of the
          // Drafts table without requiring a refetch.
          state.drafts = [d, ...state.drafts.filter((x) => x.partnerCode !== d.partnerCode)];
        }
      })
      .addCase(createDraft.rejected, (state, action) => {
        state.creatingDraft = false;
        state.error = action.error?.message ?? 'Failed to create draft';
      });
  },
});

export const { clearError } = partnersSlice.actions;
export default partnersSlice.reducer;

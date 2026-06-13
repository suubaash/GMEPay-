'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Drafts slice — backs the Partner Setup wizard introduced in Slice 1
 * (see docs/PARTNER_SETUP_PLAN.md §"Slice 1 — Identity + Foundation").
 *
 * Each in-flight wizard renders against ONE draft at a time, fetched by
 * partnerCode via {@link adminApi.getDraft}. The slice keeps:
 *   - `current`          : the PartnerView the wizard is editing right now.
 *   - `currentCode`      : the partnerCode `current` belongs to (or null).
 *   - `loading`/`saving` : two separate flags so a Next click during a
 *                          background refresh doesn't disable the form
 *                          for the wrong reason.
 *   - `error`            : last user-visible failure; cleared by clearError.
 *   - `list`             : output of {@link adminApi.listDrafts}, for the
 *                          "Drafts in progress" section on /partners.
 *
 * Only Step 1 (Identity) is implemented end-to-end in Slice 1. The
 * patchStep2..patchStep8 thunks are deliberately included anyway so the
 * wizard skeleton can wire all eight Next buttons uniformly today; they
 * surface ApiError(501) from {@link adminApi.patchDraftStep} when invoked
 * before the matching backend slice lands. That ApiError flows into
 * `state.error` exactly like a real backend failure would.
 *
 * Wire shape (PartnerView):
 *   { id, partnerCode, status, type, settlementCurrency,
 *     settlementRoundingMode, legalNameLocal, legalNameRomanized,
 *     taxId, taxIdType, countryOfIncorporation, legalForm,
 *     registeredAddress, operatingAddress, lei,
 *     validFrom, validTo, recordedAt, supersededAt }
 *
 * All money/dates are surfaced as the JSON the BFF emitted — the wizard's
 * step forms own any further parsing.
 */
const initialState = {
  current: null,
  currentCode: null,
  /** Contacts for the currently-open draft, loaded lazily when Step 2 mounts. */
  contacts: [],
  contactsLoading: false,
  list: [],
  loading: false,
  listLoading: false,
  saving: false,
  error: null,
};

export const fetchDraft = createAsyncThunk(
  'drafts/fetch',
  async (partnerCode) => {
    return adminApi.getDraft(partnerCode);
  },
);

/**
 * Fetch the contacts list for the currently-open draft.
 * Dispatched by ContactsForm on mount (Step 2) so the operator sees any
 * contacts already persisted from a previous session.
 */
export const fetchContacts = createAsyncThunk(
  'drafts/fetchContacts',
  async (partnerCode) => {
    return adminApi.getPartnerContacts(partnerCode);
  },
);

export const fetchDrafts = createAsyncThunk('drafts/list', async () => {
  return adminApi.listDrafts();
});

export const createDraft = createAsyncThunk(
  'drafts/create',
  async (body) => {
    return adminApi.createDraft(body);
  },
);

/**
 * Build a `patchStep{N}` thunk. The thunk argument shape is
 * `{ partnerCode, body }` — the partnerCode lives in the URL, not the body,
 * matching the BFF's PATCH /v1/admin/partners/draft/{partnerCode}/step-{n}.
 *
 * Sharing one factory keeps the eight reducers below in lock-step with the
 * shared status/error handling and avoids drift as Slices 2..8 land.
 */
function makePatchStep(stepNumber) {
  return createAsyncThunk(
    `drafts/patchStep${stepNumber}`,
    async ({ partnerCode, body }) => {
      return adminApi.patchDraftStep(stepNumber, partnerCode, body);
    },
  );
}

export const patchStep1 = makePatchStep(1);
export const patchStep2 = makePatchStep(2);
export const patchStep3 = makePatchStep(3);
export const patchStep4 = makePatchStep(4);
export const patchStep5 = makePatchStep(5);
export const patchStep6 = makePatchStep(6);
export const patchStep7 = makePatchStep(7);
export const patchStep8 = makePatchStep(8);

const STEP_THUNKS = [
  patchStep1,
  patchStep2,
  patchStep3,
  patchStep4,
  patchStep5,
  patchStep6,
  patchStep7,
  patchStep8,
];

const draftsSlice = createSlice({
  name: 'drafts',
  initialState,
  reducers: {
    clearError(state) {
      state.error = null;
    },
    /**
     * Clear the in-memory wizard state — used when the operator navigates
     * away from /partners/draft/* so a stale draft doesn't briefly flash
     * onto a different draft's URL on the next visit.
     */
    resetCurrent(state) {
      state.current = null;
      state.currentCode = null;
      state.contacts = [];
      state.contactsLoading = false;
      state.error = null;
      state.saving = false;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchDraft ----
      .addCase(fetchDraft.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.currentCode = action.meta.arg ?? null;
      })
      .addCase(fetchDraft.fulfilled, (state, action) => {
        state.loading = false;
        state.current = action.payload ?? null;
        if (action.payload && action.payload.partnerCode) {
          state.currentCode = action.payload.partnerCode;
        }
      })
      .addCase(fetchDraft.rejected, (state, action) => {
        state.loading = false;
        state.error = action.error?.message ?? 'Failed to load draft';
      })
      // ---- fetchContacts (Step 2) ----
      .addCase(fetchContacts.pending, (state) => {
        state.contactsLoading = true;
      })
      .addCase(fetchContacts.fulfilled, (state, action) => {
        state.contactsLoading = false;
        state.contacts = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchContacts.rejected, (state) => {
        state.contactsLoading = false;
        // Contacts fetch failure is non-fatal — the form starts with an
        // empty row so the operator can still enter contacts.
      })
      // ---- fetchDrafts (list) ----
      .addCase(fetchDrafts.pending, (state) => {
        state.listLoading = true;
        state.error = null;
      })
      .addCase(fetchDrafts.fulfilled, (state, action) => {
        state.listLoading = false;
        state.list = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchDrafts.rejected, (state, action) => {
        state.listLoading = false;
        state.error = action.error?.message ?? 'Failed to load drafts';
      })
      // ---- createDraft ----
      .addCase(createDraft.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(createDraft.fulfilled, (state, action) => {
        state.saving = false;
        const view = action.payload;
        if (view && view.partnerCode) {
          state.current = view;
          state.currentCode = view.partnerCode;
          // Prepend so the most recently created draft surfaces first in
          // the "Drafts in progress" list — matches the operator's mental
          // model when they have just clicked "New partner".
          state.list = [view, ...state.list.filter((d) => d.partnerCode !== view.partnerCode)];
        }
      })
      .addCase(createDraft.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to create draft';
      });

    // ---- patchStepN (1..8) ----
    // Use a single matcher block so adding a step in a future slice doesn't
    // require editing extraReducers. Each thunk produces `pending`,
    // `fulfilled`, `rejected` actions; saving + error reflect the shared
    // wizard state, while `current` is replaced from the payload on
    // success so the bitemporal stamps refresh.
    STEP_THUNKS.forEach((thunk) => {
      builder
        .addCase(thunk.pending, (state) => {
          state.saving = true;
          state.error = null;
        })
        .addCase(thunk.fulfilled, (state, action) => {
          state.saving = false;
          if (action.payload && action.payload.partnerCode) {
            state.current = action.payload;
            state.currentCode = action.payload.partnerCode;
          }
        })
        .addCase(thunk.rejected, (state, action) => {
          state.saving = false;
          state.error = action.error?.message ?? 'Failed to save draft step';
        });
    });
  },
});

export const { clearError, resetCurrent } = draftsSlice.actions;
export default draftsSlice.reducer;

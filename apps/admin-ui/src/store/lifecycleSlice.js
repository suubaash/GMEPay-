'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * Lifecycle slice — backs Step 8 (Review & Activate) in the Partner Setup wizard.
 *
 * Manages:
 *   preconditionsByCode : partnerCode → PreconditionView[]
 *   activationByCode    : partnerCode → { status: 'PROPOSED'|'ACTIVATED', proposedAt }
 *   issuedBundle        : IssuedCredentialBundle | null  (plaintext, one-time, in-memory only)
 *   loadingByCode       : partnerCode → boolean
 *   saving              : boolean — POST in-flight
 *   error               : last user-visible failure
 *
 * PreconditionView: { key: string, description: string, met: boolean }
 *
 * IssuedCredentialBundle: {
 *   keyId:              string,
 *   keyPrefix:          string,
 *   keyLast4:           string,
 *   plaintextApiKey:    string,   // one-time plaintext — NEVER persist
 *   plaintextHmac:      string,   // one-time plaintext
 *   plaintextWebhookSecret: string, // one-time plaintext
 * }
 *
 * Security model: the bundle lives ONLY in this slice's in-memory state.
 * After the operator dismisses the OneTimeCredentialModal, dismissBundle()
 * must be dispatched to zero out the bundle from Redux state. The backend
 * returns plaintext only on the activation response (201); subsequent GETs
 * return only keyId/prefix/last4.
 */
const initialState = {
  /** partnerCode → PreconditionView[] */
  preconditionsByCode: {},
  /** partnerCode → { status, proposedAt } */
  activationByCode: {},
  /**
   * The one-time credential bundle returned by a successful executeActivate.
   * Kept null until activation succeeds; zeroed by dismissBundle() after the
   * operator confirms they have stored the credentials.
   */
  issuedBundle: null,
  /** partnerCode → boolean */
  loadingByCode: {},
  saving: false,
  error: null,
};

/**
 * GET /v1/admin/partners/{code}/lifecycle/preconditions
 * -> PreconditionView[]
 */
export const fetchActivationPreconditions = createAsyncThunk(
  'lifecycle/fetchPreconditions',
  async (partnerCode) => {
    const items = await adminApi.getActivationPreconditions(partnerCode);
    return { partnerCode, items };
  },
);

/**
 * POST /v1/admin/partners/{code}/lifecycle/activate  (first-operator click)
 * -> 202 { status: 'PROPOSED', proposedAt: ISO }
 */
export const proposeActivate = createAsyncThunk(
  'lifecycle/proposeActivate',
  async (partnerCode) => {
    const result = await adminApi.proposePartnerActivation(partnerCode);
    return { partnerCode, result };
  },
);

/**
 * POST /v1/admin/partners/{code}/lifecycle/activate  (second-operator confirm)
 * -> 201 IssuedCredentialBundle
 *
 * Same endpoint — the backend FSM decides whether to transition to PROPOSED
 * (first call) or ACTIVE (second call from a different operator).  The thunk
 * differentiates by the response status code: 202 = proposed, 201 = activated.
 * The adminApi wrapper returns the body directly; caller checks result.activated.
 */
export const executeActivate = createAsyncThunk(
  'lifecycle/executeActivate',
  async (partnerCode) => {
    const result = await adminApi.executePartnerActivation(partnerCode);
    return { partnerCode, result };
  },
);

/**
 * PATCH /v1/admin/partners/draft/{code}/step-8/ip-allowlist
 * body: { env: 'sandbox'|'production', cidrs: string[] }
 * -> PartnerView
 */
export const patchStep8IpAllowlist = createAsyncThunk(
  'lifecycle/patchIpAllowlist',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep8IpAllowlist(partnerCode, body);
  },
);

/**
 * PATCH /v1/admin/partners/draft/{code}/step-8/mtls-cert
 * body: { pemCertificate: string }
 * -> { subjectDn, issuerDn, notBefore, notAfter, fingerprint }
 */
export const patchStep8MtlsCert = createAsyncThunk(
  'lifecycle/patchMtlsCert',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep8MtlsCert(partnerCode, body);
  },
);

/**
 * PATCH /v1/admin/partners/draft/{code}/step-8/regulatory
 * body: { bok: {...}, hometax: {...}, kofiu: {...}, pipa: {...}, travelRule: {...} }
 * -> PartnerView
 */
export const patchStep8Regulatory = createAsyncThunk(
  'lifecycle/patchRegulatory',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep8Regulatory(partnerCode, body);
  },
);

/**
 * PATCH /v1/admin/partners/draft/{code}/step-8/webhook-subscription
 * body: { url: string, eventTypes: string[] }
 * -> PartnerView
 */
export const patchStep8WebhookSubscription = createAsyncThunk(
  'lifecycle/patchWebhookSubscription',
  async ({ partnerCode, body }) => {
    return adminApi.patchDraftStep8WebhookSubscription(partnerCode, body);
  },
);

const lifecycleSlice = createSlice({
  name: 'lifecycle',
  initialState,
  reducers: {
    clearLifecycleError(state) {
      state.error = null;
    },
    /**
     * Zero out the in-memory credential bundle after the operator confirms
     * they have stored the credentials securely.  After this action fires the
     * modal cannot be reopened — the plaintext values are gone.
     */
    dismissBundle(state) {
      state.issuedBundle = null;
    },
  },
  extraReducers: (builder) => {
    builder
      // ---- fetchActivationPreconditions ----
      .addCase(fetchActivationPreconditions.pending, (state, action) => {
        state.loadingByCode[action.meta.arg] = true;
        state.error = null;
      })
      .addCase(fetchActivationPreconditions.fulfilled, (state, action) => {
        const { partnerCode, items } = action.payload;
        state.loadingByCode[partnerCode] = false;
        state.preconditionsByCode[partnerCode] = Array.isArray(items) ? items : [];
      })
      .addCase(fetchActivationPreconditions.rejected, (state, action) => {
        state.loadingByCode[action.meta.arg] = false;
        state.error = action.error?.message ?? 'Failed to load preconditions';
      })

      // ---- proposeActivate ----
      .addCase(proposeActivate.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(proposeActivate.fulfilled, (state, action) => {
        state.saving = false;
        const { partnerCode, result } = action.payload;
        state.activationByCode[partnerCode] = {
          status: 'PROPOSED',
          proposedAt: result?.proposedAt ?? null,
        };
      })
      .addCase(proposeActivate.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Activation proposal failed';
      })

      // ---- executeActivate ----
      .addCase(executeActivate.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(executeActivate.fulfilled, (state, action) => {
        state.saving = false;
        const { partnerCode, result } = action.payload;
        state.activationByCode[partnerCode] = {
          status: 'ACTIVATED',
          activatedAt: result?.activatedAt ?? null,
        };
        // Store the one-time credential bundle in memory.
        // The component will call dismissBundle() after the operator confirms.
        if (result) {
          state.issuedBundle = {
            keyId:                  result.keyId ?? null,
            keyPrefix:              result.keyPrefix ?? null,
            keyLast4:               result.keyLast4 ?? null,
            plaintextApiKey:        result.plaintextApiKey ?? null,
            plaintextHmac:          result.plaintextHmac ?? null,
            plaintextWebhookSecret: result.plaintextWebhookSecret ?? null,
          };
        }
      })
      .addCase(executeActivate.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Activation failed';
      })

      // ---- patchStep8IpAllowlist ----
      .addCase(patchStep8IpAllowlist.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(patchStep8IpAllowlist.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(patchStep8IpAllowlist.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save IP allowlist';
      })

      // ---- patchStep8MtlsCert ----
      .addCase(patchStep8MtlsCert.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(patchStep8MtlsCert.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(patchStep8MtlsCert.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to upload mTLS certificate';
      })

      // ---- patchStep8Regulatory ----
      .addCase(patchStep8Regulatory.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(patchStep8Regulatory.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(patchStep8Regulatory.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save regulatory settings';
      })

      // ---- patchStep8WebhookSubscription ----
      .addCase(patchStep8WebhookSubscription.pending, (state) => {
        state.saving = true;
        state.error = null;
      })
      .addCase(patchStep8WebhookSubscription.fulfilled, (state) => {
        state.saving = false;
      })
      .addCase(patchStep8WebhookSubscription.rejected, (state, action) => {
        state.saving = false;
        state.error = action.error?.message ?? 'Failed to save webhook subscription';
      });
  },
});

export const { clearLifecycleError, dismissBundle } = lifecycleSlice.actions;
export default lifecycleSlice.reducer;

'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';

/**
 * partnerLifecycleSlice — post-activation lifecycle management.
 *
 * Covers:
 *  - FSM transitions: propose + execute (4-eyes flow)
 *    POST /v1/admin/partners/{code}/lifecycle/propose  { action, reason, notes }
 *    POST /v1/admin/partners/{code}/lifecycle/execute  { changeRequestId }
 *  - Credential rotation:
 *    POST /v1/admin/partners/{code}/credentials/rotate  { credentialId }
 *    GET  /v1/admin/partners/{code}/credentials
 *  - Partner audit (scoped):
 *    GET  /v1/admin/partners/{code}/audit?page=&size=
 *
 * Shape:
 *   credentials: PartnerCredentialView[]
 *   credentialsLoading: boolean
 *   credentialsError: string|null
 *   rotatingId: string|null            — credentialId currently being rotated
 *   rotateResult: OneTimeCredentialView|null — returned once after a rotate
 *   partnerAudit: Record<code, { items, page, size, total, loading, error }>
 *   lifecycle: Record<code, {
 *     proposing: boolean,
 *     executing: boolean,
 *     pendingChangeRequestId: string|null,
 *     error: string|null,
 *   }>
 *
 * PartnerCredentialView:
 *   { id, env, kind, prefix, last4, issuedAt, expiresAt, status }
 *
 * OneTimeCredentialView:
 *   { id, env, kind, prefix, last4, issuedAt, expiresAt, plaintextSecret }
 *   — plaintextSecret is returned ONCE. Never log or persist it.
 */

const initialState = {
  credentials: [],
  credentialsLoading: false,
  credentialsError: null,
  rotatingId: null,
  rotateResult: null,
  // Webhook endpoint signing secrets (gap T5-8) — see the thunks below.
  webhookEndpoints: [],
  webhookEndpointsLoading: false,
  webhookEndpointsError: null,
  rotatingEndpointId: null,
  webhookRotateResult: null,
  partnerAudit: {},
  lifecycle: {},
};

// ---------- Credentials ----------

export const fetchPartnerCredentials = createAsyncThunk(
  'partnerLifecycle/fetchCredentials',
  async (partnerCode, { rejectWithValue }) => {
    try {
      return await adminApi.getPartnerCredentials(partnerCode);
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to load credentials');
    }
  },
);

export const rotateCredential = createAsyncThunk(
  'partnerLifecycle/rotateCredential',
  async ({ partnerCode, credentialId }, { rejectWithValue }) => {
    try {
      return await adminApi.rotatePartnerCredential(partnerCode, credentialId);
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to rotate credential');
    }
  },
);

// ---------- Webhook endpoint signing secrets (gap T5-8) ----------

/**
 * Which of this partner's webhook endpoints can actually be signed for.
 *
 * GET /v1/admin/webhooks/endpoints?partnerCode=
 * -> EndpointSigningHealth[] (see adminApi.getWebhookEndpointHealth)
 *
 * An endpoint registered before per-endpoint signing secrets existed holds a secret that can
 * never be re-derived, so the platform refuses to sign its deliveries — correct, but it means
 * the partner silently receives nothing until an operator rotates it.
 */
export const fetchWebhookEndpointHealth = createAsyncThunk(
  'partnerLifecycle/fetchWebhookEndpointHealth',
  async (partnerCode, { rejectWithValue }) => {
    try {
      return await adminApi.getWebhookEndpointHealth(partnerCode);
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to load webhook endpoints');
    }
  },
);

/**
 * Rotate one endpoint's webhook signing secret.
 *
 * POST /v1/admin/webhooks/endpoints/{endpointId}/rotate-secret  { reason?, overlapMinutes? }
 * -> { endpointId, signingSecretPlaintext, secretGeneration, previousSecretExpiresAt }
 *
 * The returned plaintext is shown ONCE (WebhookSecretRevealModal) and is unrecoverable after
 * that — it is never written to the store beyond the modal's lifetime, never logged, and must
 * be handed to the partner out of band. Requires `ops:operate`; a token without it gets 403.
 */
export const rotateWebhookEndpointSecret = createAsyncThunk(
  'partnerLifecycle/rotateWebhookEndpointSecret',
  async ({ endpointId, reason, overlapMinutes }, { rejectWithValue }) => {
    try {
      return await adminApi.rotateWebhookEndpointSecret(endpointId, { reason, overlapMinutes });
    } catch (e) {
      return rejectWithValue(e?.message ?? 'Failed to rotate webhook signing secret');
    }
  },
);

// ---------- Partner-scoped audit ----------

export const fetchPartnerAudit = createAsyncThunk(
  'partnerLifecycle/fetchAudit',
  async ({ partnerCode, page = 0, size = 20 }, { rejectWithValue }) => {
    try {
      const data = await adminApi.getPartnerAuditPage(partnerCode, page, size);
      return { partnerCode, ...data };
    } catch (e) {
      return rejectWithValue({ partnerCode, message: e?.message ?? 'Failed to load audit' });
    }
  },
);

// ---------- Lifecycle FSM ----------

/**
 * Propose a lifecycle transition (first leg of 4-eyes).
 *
 * POST /v1/admin/partners/{code}/lifecycle/propose
 * body: { action: 'SUSPEND'|'REACTIVATE'|'TERMINATE', reason, notes? }
 * -> LifecycleChangeRequestView { changeRequestId, action, status:'PROPOSED', proposedBy, proposedAt }
 */
export const proposeLifecycleTransition = createAsyncThunk(
  'partnerLifecycle/propose',
  async ({ partnerCode, action, reason, notes }, { rejectWithValue }) => {
    try {
      const data = await adminApi.proposeLifecycleTransition(partnerCode, { action, reason, notes });
      return { partnerCode, ...data };
    } catch (e) {
      return rejectWithValue({ partnerCode, message: e?.message ?? 'Failed to propose transition' });
    }
  },
);

/**
 * Execute a previously proposed lifecycle transition (second operator leg).
 *
 * POST /v1/admin/partners/{code}/lifecycle/execute
 * body: { changeRequestId }
 * -> PartnerView with updated status
 */
export const executeLifecycleTransition = createAsyncThunk(
  'partnerLifecycle/execute',
  async ({ partnerCode, changeRequestId }, { rejectWithValue }) => {
    try {
      const data = await adminApi.executeLifecycleTransition(partnerCode, changeRequestId);
      return { partnerCode, ...data };
    } catch (e) {
      return rejectWithValue({ partnerCode, message: e?.message ?? 'Failed to execute transition' });
    }
  },
);

// ---------- Slice ----------

const partnerLifecycleSlice = createSlice({
  name: 'partnerLifecycle',
  initialState,
  reducers: {
    clearRotateResult(state) {
      state.rotateResult = null;
    },
    /**
     * Drop the revealed webhook signing secret. Called when the reveal modal closes — the
     * plaintext must not outlive the dialog that showed it.
     */
    clearWebhookRotateResult(state) {
      state.webhookRotateResult = null;
    },
    clearLifecycleError(state, action) {
      const code = action.payload;
      if (code && state.lifecycle[code]) {
        state.lifecycle[code].error = null;
      }
    },
  },
  extraReducers: (builder) => {
    // --- fetchPartnerCredentials ---
    builder
      .addCase(fetchPartnerCredentials.pending, (state) => {
        state.credentialsLoading = true;
        state.credentialsError = null;
      })
      .addCase(fetchPartnerCredentials.fulfilled, (state, action) => {
        state.credentialsLoading = false;
        state.credentials = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchPartnerCredentials.rejected, (state, action) => {
        state.credentialsLoading = false;
        state.credentialsError = action.payload ?? action.error?.message ?? 'Failed to load credentials';
      });

    // --- rotateCredential ---
    builder
      .addCase(rotateCredential.pending, (state, action) => {
        state.rotatingId = action.meta.arg.credentialId;
        state.rotateResult = null;
      })
      .addCase(rotateCredential.fulfilled, (state, action) => {
        state.rotatingId = null;
        state.rotateResult = action.payload;
        // Update the credential row in the list (status may change to ACTIVE, old ACTIVE→SUPERSEDED)
        if (Array.isArray(action.payload?.allCredentials)) {
          state.credentials = action.payload.allCredentials;
        }
      })
      .addCase(rotateCredential.rejected, (state) => {
        state.rotatingId = null;
      });

    // --- fetchWebhookEndpointHealth (T5-8) ---
    builder
      .addCase(fetchWebhookEndpointHealth.pending, (state) => {
        state.webhookEndpointsLoading = true;
        state.webhookEndpointsError = null;
      })
      .addCase(fetchWebhookEndpointHealth.fulfilled, (state, action) => {
        state.webhookEndpointsLoading = false;
        state.webhookEndpoints = Array.isArray(action.payload) ? action.payload : [];
      })
      .addCase(fetchWebhookEndpointHealth.rejected, (state, action) => {
        state.webhookEndpointsLoading = false;
        state.webhookEndpointsError =
          action.payload ?? action.error?.message ?? 'Failed to load webhook endpoints';
      });

    // --- rotateWebhookEndpointSecret (T5-8) ---
    builder
      .addCase(rotateWebhookEndpointSecret.pending, (state, action) => {
        state.rotatingEndpointId = action.meta.arg.endpointId;
        state.webhookRotateResult = null;
      })
      .addCase(rotateWebhookEndpointSecret.fulfilled, (state, action) => {
        state.rotatingEndpointId = null;
        state.webhookRotateResult = action.payload;
      })
      .addCase(rotateWebhookEndpointSecret.rejected, (state) => {
        state.rotatingEndpointId = null;
      });

    // --- fetchPartnerAudit ---
    builder
      .addCase(fetchPartnerAudit.pending, (state, action) => {
        const { partnerCode } = action.meta.arg;
        state.partnerAudit[partnerCode] = {
          ...(state.partnerAudit[partnerCode] ?? {}),
          loading: true,
          error: null,
        };
      })
      .addCase(fetchPartnerAudit.fulfilled, (state, action) => {
        const { partnerCode, content, page, size, total } = action.payload ?? {};
        if (partnerCode) {
          state.partnerAudit[partnerCode] = {
            items: Array.isArray(content) ? content : [],
            page: page ?? 0,
            size: size ?? 20,
            total: total ?? 0,
            loading: false,
            error: null,
          };
        }
      })
      .addCase(fetchPartnerAudit.rejected, (state, action) => {
        const { partnerCode, message } = action.payload ?? {};
        if (partnerCode) {
          state.partnerAudit[partnerCode] = {
            ...(state.partnerAudit[partnerCode] ?? {}),
            loading: false,
            error: message ?? action.error?.message ?? 'Failed to load audit',
          };
        }
      });

    // --- proposeLifecycleTransition ---
    builder
      .addCase(proposeLifecycleTransition.pending, (state, action) => {
        const { partnerCode } = action.meta.arg;
        state.lifecycle[partnerCode] = {
          ...(state.lifecycle[partnerCode] ?? {}),
          proposing: true,
          error: null,
        };
      })
      .addCase(proposeLifecycleTransition.fulfilled, (state, action) => {
        const { partnerCode, changeRequestId } = action.payload ?? {};
        if (partnerCode) {
          state.lifecycle[partnerCode] = {
            proposing: false,
            executing: false,
            pendingChangeRequestId: changeRequestId ?? null,
            error: null,
          };
        }
      })
      .addCase(proposeLifecycleTransition.rejected, (state, action) => {
        const { partnerCode, message } = action.payload ?? {};
        if (partnerCode) {
          state.lifecycle[partnerCode] = {
            ...(state.lifecycle[partnerCode] ?? {}),
            proposing: false,
            error: message ?? action.error?.message ?? 'Failed to propose transition',
          };
        }
      });

    // --- executeLifecycleTransition ---
    builder
      .addCase(executeLifecycleTransition.pending, (state, action) => {
        const { partnerCode } = action.meta.arg;
        state.lifecycle[partnerCode] = {
          ...(state.lifecycle[partnerCode] ?? {}),
          executing: true,
          error: null,
        };
      })
      .addCase(executeLifecycleTransition.fulfilled, (state, action) => {
        const { partnerCode } = action.payload ?? {};
        if (partnerCode) {
          state.lifecycle[partnerCode] = {
            proposing: false,
            executing: false,
            pendingChangeRequestId: null,
            error: null,
          };
        }
      })
      .addCase(executeLifecycleTransition.rejected, (state, action) => {
        const { partnerCode, message } = action.payload ?? {};
        if (partnerCode) {
          state.lifecycle[partnerCode] = {
            ...(state.lifecycle[partnerCode] ?? {}),
            executing: false,
            error: message ?? action.error?.message ?? 'Failed to execute transition',
          };
        }
      });
  },
});

export const { clearRotateResult, clearWebhookRotateResult, clearLifecycleError } =
  partnerLifecycleSlice.actions;
export default partnerLifecycleSlice.reducer;

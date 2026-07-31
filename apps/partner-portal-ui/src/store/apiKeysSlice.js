'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';

/**
 * API keys slice.
 *
 * Wire shape — GET /v1/portal/{partnerId}/api-keys returns
 *   Array<ApiKeyView>
 *     {
 *       keyId: string,
 *       name: null,                  // always null — api_keys has no name column
 *       prefix: string,
 *       scopes: [],                  // always empty — per-key scopes aren't modelled
 *       createdAt: string,           // ISO instant
 *       lastUsedAt: null,            // always null — key usage isn't recorded
 *       status: 'ACTIVE' | 'PENDING_EXPIRY' | 'REVOKED',
 *       environment: 'SANDBOX' | 'PRODUCTION',
 *       expiresAt: string | null     // ISO instant or null (no expiry configured)
 *     }
 *
 * Gap register T1-3: these are the partner's REAL credentials from auth-identity's
 * api_keys registry. The page used to render two fabricated keys per partner
 * (gpk_live_<hash> prefixes, PRIMARY/ROTATING statuses, invented scopes and a
 * last-used time) because the BFF had no rest client. The three fields marked
 * "always" above have no source in the platform and render as an em dash — they
 * are deliberately NOT back-filled.
 *
 * Phase 1 is READ-ONLY: there is no rotate/revoke action yet. The page shows
 * a banner pointing operators at Ops/Admin (or auth-identity self-service)
 * for that workflow in Phase 2.
 *
 * State: { data, status, error }
 *   data: ApiKeyView[] | null
 *   status: 'idle' | 'loading' | 'succeeded' | 'failed'
 */

const initialState = { data: null, status: 'idle', error: null };

export const fetchApiKeys = createAsyncThunk(
  'apiKeys/fetch',
  async (partnerId) => portalApi.listApiKeys(partnerId)
);

const slice = createSlice({
  name: 'apiKeys',
  initialState,
  reducers: {
    resetApiKeys: () => initialState
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchApiKeys.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(fetchApiKeys.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.data = Array.isArray(a.payload) ? a.payload : [];
      })
      .addCase(fetchApiKeys.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.error.message ?? 'Failed to load API keys';
      });
  }
});

export const { resetApiKeys } = slice.actions;
export default slice.reducer;

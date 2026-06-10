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
 *       name: string,
 *       prefix: string,
 *       scopes: string[],
 *       createdAt: string,           // ISO instant
 *       lastUsedAt: string | null,   // ISO instant
 *       status: 'ACTIVE' | 'ROTATING' | 'REVOKED'
 *     }
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

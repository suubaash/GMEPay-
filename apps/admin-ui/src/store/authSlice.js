'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { clearAuth, storeOidcSession } from '@/api/auth';
import { decodeJwtPayload } from '@/api/oidc';

/**
 * Operator auth state. Keycloak OIDC is the only way in — the `loginThunk` that
 * POSTed to the BFF's `/v1/auth/login` is deleted along with the endpoint itself
 * (gap T0-1); an unsigned BFF token is rejected by the resource server.
 *
 * @typedef {Object} AuthState
 * @property {string|null} token         Keycloak access_token (the BFF bearer).
 * @property {string|null} username      `preferred_username` from the id_token.
 * @property {string|null} role          First realm role from realm_access.roles.
 * @property {string|null} expiresAt     Token expiry, ms-since-epoch as a string.
 * @property {boolean} loading
 * @property {string|null} error
 */

const initialState = {
  token: null,
  username: null,
  role: null,
  expiresAt: null,
  loading: false,
  error: null,
};

/**
 * Persist an OIDC token response into localStorage + the Redux slice.
 *
 * Called by the `/auth/callback` page after a successful code exchange.
 * The token response shape is whatever Keycloak's /token endpoint returned:
 * `{ access_token, id_token, refresh_token, expires_in, token_type, scope }`.
 *
 * The thunk side-effects via {@link storeOidcSession} (one place owns the
 * localStorage write); the fulfilled reducer below then mirrors the
 * user-facing fields into the slice for components that read from Redux
 * (e.g. AppShell).
 */
export const applyOidcSessionThunk = createAsyncThunk(
  'auth/applyOidcSession',
  async (tokenResponse, { rejectWithValue }) => {
    try {
      storeOidcSession(tokenResponse);
      const claims = decodeJwtPayload(tokenResponse.id_token) ?? {};
      const username =
        claims.preferred_username ?? claims.email ?? claims.name ?? null;
      const role = claims.realm_access?.roles?.[0] ?? null;
      const expiresAtMs =
        Number.isFinite(tokenResponse.expires_in)
          ? Date.now() + tokenResponse.expires_in * 1000
          : null;
      return {
        token: tokenResponse.access_token ?? null,
        username,
        role,
        expiresAt: expiresAtMs != null ? String(expiresAtMs) : null,
      };
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return rejectWithValue(msg);
    }
  },
);

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    /** Clear local auth state (called from the AppShell logout button). */
    logout(state) {
      clearAuth();
      state.token = null;
      state.username = null;
      state.role = null;
      state.expiresAt = null;
      state.error = null;
    },
    /**
     * Hydrate auth state from localStorage on mount (the slice itself can't
     * read localStorage at construction time because the store is initialised
     * on the server).
     */
    hydrate(state, action) {
      state.token = action.payload?.token ?? null;
      state.username = action.payload?.username ?? null;
      state.role = action.payload?.role ?? null;
      state.expiresAt = action.payload?.expiresAt ?? null;
    },
    clearError(state) {
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(applyOidcSessionThunk.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(applyOidcSessionThunk.fulfilled, (state, action) => {
        state.loading = false;
        state.error = null;
        state.token = action.payload?.token ?? null;
        state.username = action.payload?.username ?? null;
        state.role = action.payload?.role ?? null;
        state.expiresAt = action.payload?.expiresAt ?? null;
      })
      .addCase(applyOidcSessionThunk.rejected, (state, action) => {
        state.loading = false;
        state.error =
          (action.payload) ??
          action.error?.message ??
          'OIDC session apply failed';
      });
  },
});

export const { logout, hydrate, clearError } = authSlice.actions;
export default authSlice.reducer;

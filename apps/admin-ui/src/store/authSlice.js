'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import {
  clearAuth,
  setToken,
  storeOidcSession,
  USER_KEY,
  EXPIRES_AT_KEY,
  ROLE_KEY,
} from '@/api/auth';
import { decodeJwtPayload } from '@/api/oidc';

/**
 * @typedef {Object} AuthState
 * @property {string|null} token         Bearer access token (BFF JWT in dev-skip
 *                                        mode, Keycloak access_token in OIDC mode).
 * @property {string|null} username      For dev-skip: form input. For OIDC:
 *                                        `preferred_username` from the id_token.
 * @property {string|null} role          For dev-skip: BFF `role`. For OIDC:
 *                                        first realm role from realm_access.roles.
 * @property {string|null} expiresAt     Token expiry; ISO string for dev-skip,
 *                                        ms-since-epoch (as string) for OIDC.
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
 * Exchange username/password for a JWT via POST /v1/auth/login on the BFF.
 *
 * Retained only for the dev-skip escape hatch (NEXT_PUBLIC_ALLOW_DEV_LOGIN=true).
 * The production login path now goes through Keycloak via OIDC + PKCE; see
 * {@link applyOidcSessionThunk}.
 *
 * BFF returns { token, expiresAt, role }. The username is the form input;
 * we cache it ourselves so the AppShell can show it without an extra call.
 */
export const loginThunk = createAsyncThunk(
  'auth/login',
  async (req, { rejectWithValue }) => {
    try {
      const res = await adminApi.login(req);
      setToken(res.token);
      if (typeof window !== 'undefined') {
        try {
          window.localStorage.setItem(USER_KEY, req.username);
          if (res.expiresAt) {
            window.localStorage.setItem(EXPIRES_AT_KEY, String(res.expiresAt));
          }
          if (res.role) {
            window.localStorage.setItem(ROLE_KEY, res.role);
          }
        } catch {
          /* ignore quota errors */
        }
      }
      return { ...res, username: req.username };
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return rejectWithValue(msg);
    }
  },
);

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
      .addCase(loginThunk.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(loginThunk.fulfilled, (state, action) => {
        state.loading = false;
        state.token = action.payload?.token ?? null;
        state.username = action.payload?.username ?? null;
        state.role = action.payload?.role ?? null;
        state.expiresAt = action.payload?.expiresAt ?? null;
      })
      .addCase(loginThunk.rejected, (state, action) => {
        state.loading = false;
        state.error =
          (action.payload) ??
          action.error?.message ??
          'Login failed';
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

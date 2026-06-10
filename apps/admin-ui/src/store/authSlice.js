'use client';

import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import { clearAuth, setToken, USER_KEY, EXPIRES_AT_KEY, ROLE_KEY } from '@/api/auth';

/**
 * @typedef {Object} AuthState
 * @property {string|null} token         JWT from POST /v1/auth/login.
 * @property {string|null} username      Form input — BFF does NOT echo it.
 * @property {string|null} role          BFF `role` (e.g. "ADMIN").
 * @property {string|null} expiresAt     ISO instant of expiry.
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
 * Exchange credentials for a JWT via POST /v1/auth/login on the BFF.
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
      });
  },
});

export const { logout, hydrate, clearError } = authSlice.actions;
export default authSlice.reducer;

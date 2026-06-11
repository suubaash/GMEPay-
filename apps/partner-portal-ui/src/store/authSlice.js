'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import { getPartnerId, getToken, logout as authLogout } from '@/api/auth';

/**
 * Auth slice.
 *
 * Mirrors the localStorage-backed auth state (token + partnerId) into Redux so
 * components can subscribe to "am I signed in?" without each one reading
 * localStorage directly.
 *
 * BFF wire contract:
 *   POST /v1/auth/login  body { username, password } -> { token, expiresAt, role }
 *
 * The form's `partnerId` field is what we send as `username` on the wire AND
 * what we mirror locally for X-Partner-Id. The BFF does not return a
 * partnerId — `api/auth.login()` synthesizes one onto the LoginResponse so
 * the slice + UI have a stable shape.
 *
 * State:
 *   { partnerId: string|null, token: string|null, role: string|null,
 *     status: 'idle'|'loading'|'succeeded'|'failed', error: string|null }
 */

const initialState = {
  partnerId: null,
  token: null,
  role: null,
  status: 'idle',
  error: null
};

export const loginThunk = createAsyncThunk(
  'auth/login',
  async (req, { rejectWithValue }) => {
    try {
      return await portalApi.login(req);
    } catch (e) {
      return rejectWithValue(e instanceof Error ? e.message : 'Login failed');
    }
  }
);

const slice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    /**
     * Hydrate the slice from localStorage on mount. Auth state is the source
     * of truth in localStorage (so it survives reloads) — Redux mirrors it so
     * components can react without calling getToken() directly.
     */
    hydrateFromStorage: (s) => {
      s.token = getToken();
      s.partnerId = getPartnerId();
      s.status = s.token && s.partnerId ? 'succeeded' : 'idle';
      s.error = null;
    },
    logoutAction: (s) => {
      authLogout();
      s.token = null;
      s.partnerId = null;
      s.role = null;
      s.status = 'idle';
      s.error = null;
    },
    clearAuthError: (s) => {
      s.error = null;
    }
  },
  extraReducers: (builder) => {
    builder
      .addCase(loginThunk.pending, (s) => {
        s.status = 'loading';
        s.error = null;
      })
      .addCase(loginThunk.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.token = a.payload.token;
        s.partnerId = a.payload.partnerId ?? null;
        s.role = a.payload.role ?? null;
        s.error = null;
      })
      .addCase(loginThunk.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.payload ?? a.error.message ?? 'Login failed';
      });
  }
});

export const { hydrateFromStorage, logoutAction, clearAuthError } = slice.actions;
export default slice.reducer;

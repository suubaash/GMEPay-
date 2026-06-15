'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import {
  getPartnerId,
  getToken,
  logout as authLogout,
  storeOidcSession,
  clearAuth,
  EXPIRES_AT_KEY
} from '@/api/auth';
import { decodeJwtPayload } from '@/api/oidc';

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
  expiresAt: null,
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

/**
 * Persist an OIDC token response into localStorage + the Redux slice.
 *
 * Called by the `/auth/callback` page after a successful code exchange.
 * The token response is whatever Keycloak's /token endpoint returned:
 * `{ access_token, id_token, refresh_token, expires_in, token_type, scope }`.
 *
 * Side-effects via {@link storeOidcSession} (one place owns the localStorage
 * write); the fulfilled reducer mirrors the user-facing fields into the slice.
 */
export const applyOidcSessionThunk = createAsyncThunk(
  'auth/applyOidcSession',
  async (tokenResponse, { rejectWithValue }) => {
    try {
      storeOidcSession(tokenResponse);
      const claims = decodeJwtPayload(tokenResponse.id_token) ?? {};
      const partnerId =
        claims.partner_id ??
        claims.preferred_username ??
        claims.email ??
        null;
      const role = claims.realm_access?.roles?.[0] ?? null;
      const expiresAtMs = Number.isFinite(tokenResponse.expires_in)
        ? Date.now() + tokenResponse.expires_in * 1000
        : null;
      return {
        token: tokenResponse.access_token ?? null,
        partnerId,
        role,
        expiresAt: expiresAtMs != null ? String(expiresAtMs) : null,
      };
    } catch (e) {
      return rejectWithValue(e instanceof Error ? e.message : String(e));
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
      // Read expiry if present (OIDC path stores ms-since-epoch).
      const epoch = (typeof window !== 'undefined')
        ? (() => {
            try { return window.localStorage.getItem(EXPIRES_AT_KEY); } catch { return null; }
          })()
        : null;
      s.expiresAt = epoch ?? null;
      s.status = s.token ? 'succeeded' : 'idle';
      s.error = null;
    },
    logoutAction: (s) => {
      clearAuth();
      s.token = null;
      s.partnerId = null;
      s.role = null;
      s.expiresAt = null;
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
      })
      .addCase(applyOidcSessionThunk.fulfilled, (s, a) => {
        s.status = 'succeeded';
        s.error = null;
        s.token = a.payload?.token ?? null;
        s.partnerId = a.payload?.partnerId ?? null;
        s.role = a.payload?.role ?? null;
        s.expiresAt = a.payload?.expiresAt ?? null;
      })
      .addCase(applyOidcSessionThunk.rejected, (s, a) => {
        s.status = 'failed';
        s.error = a.payload ?? a.error?.message ?? 'OIDC session apply failed';
      });
  }
});

export const { hydrateFromStorage, logoutAction, clearAuthError } = slice.actions;
export default slice.reducer;


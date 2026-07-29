'use client';
import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import {
  getPartnerId,
  getToken,
  logout as authLogout,
  partnerIdFromTokens,
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
 * There is exactly ONE way in: Keycloak authorization-code + PKCE, applied by
 * {@link applyOidcSessionThunk} from the `/auth/callback` page. The old
 * `loginThunk` (POST /v1/auth/login with `password=demo`) is deleted — that BFF
 * endpoint no longer exists (gap T0-1) and the token it minted is rejected by
 * the resource server.
 *
 * `partnerId` is the token's `partner_id` claim, never a form field: it is what
 * the BFF compares the `/v1/portal/{partnerId}/**` path segment against.
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
      // partner_id claim only — a username here would mean a 403 on every call.
      const partnerId = partnerIdFromTokens(tokenResponse);
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
      .addCase(applyOidcSessionThunk.pending, (s) => {
        s.status = 'loading';
        s.error = null;
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


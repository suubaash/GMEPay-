'use client';
import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import { portalApi } from '@/api/client';
import { getPartnerId, getToken, logout as authLogout } from '@/api/auth';
import type { LoginRequest, LoginResponse } from '@/api/auth';
import type { LoadState } from './overviewSlice';

interface AuthState {
  partnerId: string | null;
  token: string | null;
  status: LoadState;
  error: string | null;
}

const initialState: AuthState = {
  partnerId: null,
  token: null,
  status: 'idle',
  error: null
};

export const loginThunk = createAsyncThunk<LoginResponse, LoginRequest>(
  'auth/login',
  async (req, { rejectWithValue }) => {
    try {
      return await portalApi.login(req);
    } catch (e) {
      return rejectWithValue(
        e instanceof Error ? e.message : 'Login failed'
      );
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
     * components can react without each one calling `getToken()` directly.
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
      .addCase(loginThunk.fulfilled, (s, a: PayloadAction<LoginResponse>) => {
        s.status = 'succeeded';
        s.token = a.payload.token;
        s.partnerId = a.payload.partnerId;
        s.error = null;
      })
      .addCase(loginThunk.rejected, (s, a) => {
        s.status = 'failed';
        s.error =
          (a.payload as string | undefined) ??
          a.error.message ??
          'Login failed';
      });
  }
});

export const { hydrateFromStorage, logoutAction, clearAuthError } = slice.actions;
export default slice.reducer;

'use client';

import { createAsyncThunk, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { adminApi } from '@/api/client';
import { clearAuth, setToken } from '@/api/auth';
import type { LoginRequest, LoginResponse } from '@/api/types';

export interface AuthState {
  token: string | null;
  username: string | null;
  roles: string[];
  loading: boolean;
  error: string | null;
}

const initialState: AuthState = {
  token: null,
  username: null,
  roles: [],
  loading: false,
  error: null,
};

/**
 * Exchange credentials for a JWT via POST /v1/auth/login on the BFF.
 *
 * The token is persisted into localStorage by api/auth.setToken so that the
 * fetch client picks it up on subsequent requests (including page reloads).
 */
export const loginThunk = createAsyncThunk(
  'auth/login',
  async (req: LoginRequest, { rejectWithValue }) => {
    try {
      const res = await adminApi.login(req);
      setToken(res.token);
      if (typeof window !== 'undefined') {
        try {
          window.localStorage.setItem('gmepay.adminUser', res.username);
        } catch {
          /* ignore quota errors */
        }
      }
      return res;
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
      state.roles = [];
      state.error = null;
    },
    /**
     * Hydrate auth state from localStorage on mount (the slice itself can't
     * read localStorage at construction time because the store is initialised
     * on the server).
     */
    hydrate(state, action: PayloadAction<{ token: string | null; username: string | null }>) {
      state.token = action.payload.token;
      state.username = action.payload.username;
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
      .addCase(loginThunk.fulfilled, (state, action: PayloadAction<LoginResponse>) => {
        state.loading = false;
        state.token = action.payload.token;
        state.username = action.payload.username;
        state.roles = action.payload.roles;
      })
      .addCase(loginThunk.rejected, (state, action) => {
        state.loading = false;
        state.error =
          (action.payload as string) ??
          action.error.message ??
          'Login failed';
      });
  },
});

export const { logout, hydrate, clearError } = authSlice.actions;
export default authSlice.reducer;

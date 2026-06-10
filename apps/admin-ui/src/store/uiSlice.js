'use client';

import { createSlice } from '@reduxjs/toolkit';

/**
 * UI slice — chrome-level toggles that don't belong to any backend resource.
 *
 * Currently only the MUI theme palette mode lives here. Persisted in
 * localStorage under "gmepay.ui.mode" by the AppShell's toggle handler, and
 * hydrated from there on mount so the user's preference survives reloads.
 *
 * Shape: { mode: "light" | "dark" }
 */
export const UI_MODE_KEY = 'gmepay.ui.mode';

const initialState = {
  mode: 'light',
};

const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    /** Flip the palette mode (light <-> dark) and persist to localStorage. */
    toggleMode(state) {
      state.mode = state.mode === 'dark' ? 'light' : 'dark';
      if (typeof window !== 'undefined') {
        try {
          window.localStorage.setItem(UI_MODE_KEY, state.mode);
        } catch {
          /* quota / disabled — ignore */
        }
      }
    },
    /** Set the palette mode explicitly. Used to hydrate from localStorage on mount. */
    setMode(state, action) {
      const next = action.payload === 'dark' ? 'dark' : 'light';
      state.mode = next;
      if (typeof window !== 'undefined') {
        try {
          window.localStorage.setItem(UI_MODE_KEY, next);
        } catch {
          /* ignore */
        }
      }
    },
    /** Hydrate the mode WITHOUT writing back to localStorage (no echo on bootstrap). */
    hydrateMode(state, action) {
      state.mode = action.payload === 'dark' ? 'dark' : 'light';
    },
  },
});

export const { toggleMode, setMode, hydrateMode } = uiSlice.actions;
export default uiSlice.reducer;

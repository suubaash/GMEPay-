'use client';
import { createSlice } from '@reduxjs/toolkit';

/**
 * UI preferences slice (dark mode, etc).
 *
 * The theme mode is persisted to localStorage under
 * `gmepay.partner-ui.mode` so it survives reloads. We read it on slice
 * initialization (SSR-safe — `window` is checked) so the first render
 * matches the user's last choice.
 *
 * Mirrors the admin-ui pattern (one prefs slice fed by MUI's `createTheme`
 * at render time).
 *
 * State:
 *   {
 *     mode: 'light' | 'dark'
 *   }
 */

export const UI_MODE_KEY = 'gmepay.partner-ui.mode';

function readPersistedMode() {
  if (typeof window === 'undefined') return 'light';
  try {
    const raw = window.localStorage.getItem(UI_MODE_KEY);
    if (raw === 'dark' || raw === 'light') return raw;
  } catch {
    // localStorage may be unavailable (private mode); fall back to default
  }
  return 'light';
}

function writePersistedMode(mode) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(UI_MODE_KEY, mode);
  } catch {
    // best-effort persistence; ignore quota/permission errors
  }
}

const initialState = {
  mode: readPersistedMode()
};

const slice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    setMode: (s, a) => {
      const next = a.payload === 'dark' ? 'dark' : 'light';
      s.mode = next;
      writePersistedMode(next);
    },
    toggleMode: (s) => {
      const next = s.mode === 'dark' ? 'light' : 'dark';
      s.mode = next;
      writePersistedMode(next);
    }
  }
});

export const { setMode, toggleMode } = slice.actions;
export default slice.reducer;

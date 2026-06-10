'use client';

import { createTheme } from '@mui/material/styles';

/**
 * GMEPay+ Ops/Admin Portal MUI theme.
 *
 * Brand: deep navy blue primary (trust / fintech), slate gray secondary.
 * Two palettes are supported — light (default) and dark — selected at
 * runtime via {@link buildTheme}. ThemeRegistry consumes the active mode
 * from uiSlice (`state.ui.mode`).
 *
 * Brand primary:
 *   - light mode : #1F3864 (deep blue, AA on white)
 *   - dark  mode : #4B86D6 (lighter blue, AA on dark surfaces)
 */

const LIGHT_PRIMARY = '#1F3864';
const DARK_PRIMARY = '#4B86D6';

function paletteFor(mode) {
  if (mode === 'dark') {
    return {
      mode: 'dark',
      primary: {
        main: DARK_PRIMARY,
        light: '#7AAEF0',
        dark: '#1F3864',
        contrastText: '#0B1220',
      },
      secondary: {
        main: '#94A3B8',
        light: '#CBD5E1',
        dark: '#64748B',
        contrastText: '#0B1220',
      },
      background: {
        default: '#0B1220',
        paper: '#111B30',
      },
      text: {
        primary: '#E2E8F0',
        secondary: '#94A3B8',
      },
      success: { main: '#34D399' },
      warning: { main: '#FBBF24' },
      error: { main: '#F87171' },
      info: { main: '#60A5FA' },
    };
  }
  return {
    mode: 'light',
    primary: {
      main: LIGHT_PRIMARY,
      light: '#3D5A8C',
      dark: '#142547',
      contrastText: '#FFFFFF',
    },
    secondary: {
      main: '#64748B',
      light: '#94A3B8',
      dark: '#334155',
      contrastText: '#FFFFFF',
    },
    background: {
      default: '#F5F7FA',
      paper: '#FFFFFF',
    },
    text: {
      primary: '#0B1220',
      secondary: '#475569',
    },
    success: { main: '#10B981' },
    warning: { main: '#F59E0B' },
    error: { main: '#DC2626' },
    info: { main: '#2563EB' },
  };
}

/**
 * Build a complete MUI theme for the given palette mode.
 *
 * @param {'light'|'dark'} mode
 */
export function buildTheme(mode) {
  return createTheme({
    palette: paletteFor(mode === 'dark' ? 'dark' : 'light'),
    typography: {
      fontFamily:
        'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
      h1: { fontSize: '2rem', fontWeight: 600, lineHeight: 1.2 },
      h2: { fontSize: '1.625rem', fontWeight: 600, lineHeight: 1.2 },
      h3: { fontSize: '1.375rem', fontWeight: 600, lineHeight: 1.2 },
      h4: { fontSize: '1.125rem', fontWeight: 600, lineHeight: 1.2 },
    },
    shape: { borderRadius: 8 },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
      },
    },
  });
}

/**
 * @deprecated Prefer {@link buildTheme}(mode). Retained for backwards
 * compatibility with tests that import `theme` directly; defaults to light.
 */
export const theme = buildTheme('light');

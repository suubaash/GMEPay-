'use client';

import { createTheme } from '@mui/material/styles';

/**
 * GMEPay+ Ops/Admin Portal MUI theme.
 *
 * Brand: deep navy blue primary (trust / fintech), slate gray secondary.
 * Used by the root layout's ThemeProvider; do not mutate at runtime.
 */
export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#0A2540',
      light: '#1F3F66',
      dark: '#061829',
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
    success: { main: '#10B981' },
    warning: { main: '#F59E0B' },
    error: { main: '#DC2626' },
    info: { main: '#2563EB' },
  },
  typography: {
    fontFamily:
      'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    h1: { fontSize: '2rem', fontWeight: 600 },
    h2: { fontSize: '1.625rem', fontWeight: 600 },
    h3: { fontSize: '1.375rem', fontWeight: 600 },
    h4: { fontSize: '1.125rem', fontWeight: 600 },
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

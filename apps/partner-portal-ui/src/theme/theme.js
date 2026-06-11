'use client';
import { createTheme } from '@mui/material/styles';

/**
 * Partner Portal theme.
 *
 * Two MUI palettes are exported:
 *   - `partnerTheme`         — the light theme (the default; what existing
 *                              pages have been built against).
 *   - `partnerThemeDark`     — the dark companion, same brand-blue primary
 *                              tuned for AA contrast on a navy background.
 *
 * The active mode is picked by `getPartnerTheme(mode)` from
 * `store/uiSlice.js` — `<Providers>` reads `state.ui.mode` and passes the
 * resolved theme into MUI's `<ThemeProvider>`.
 *
 * Brand: same GMEPay primary blue as admin-ui but a lighter, airier
 * background so partners feel they're in a self-service product, not an
 * operator console.
 */

const SHARED = {
  shape: {
    borderRadius: 10
  },
  typography: {
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
    h1: { fontSize: '1.75rem', fontWeight: 600 },
    h2: { fontSize: '1.5rem', fontWeight: 600 },
    h3: { fontSize: '1.25rem', fontWeight: 600 },
    h4: { fontSize: '1.125rem', fontWeight: 600 },
    button: { textTransform: 'none', fontWeight: 600 }
  }
};

export const partnerTheme = createTheme({
  ...SHARED,
  palette: {
    mode: 'light',
    primary: {
      main: '#0B5FFF',
      light: '#5C8DFF',
      dark: '#0742B8',
      contrastText: '#FFFFFF'
    },
    secondary: {
      main: '#00A88E',
      contrastText: '#FFFFFF'
    },
    background: {
      default: '#F6F9FE',
      paper: '#FFFFFF'
    },
    success: { main: '#16A34A' },
    warning: { main: '#D97706' },
    error: { main: '#DC2626' },
    text: {
      primary: '#0F172A',
      secondary: '#475569'
    }
  },
  components: {
    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'inherit' },
      styleOverrides: {
        root: {
          borderBottom: '1px solid #E2E8F0',
          backgroundColor: '#FFFFFF'
        }
      }
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: '1px solid #E2E8F0'
        }
      }
    },
    MuiButton: {
      defaultProps: { disableElevation: true }
    }
  }
});

export const partnerThemeDark = createTheme({
  ...SHARED,
  palette: {
    mode: 'dark',
    primary: {
      main: '#5C8DFF',
      light: '#8DB1FF',
      dark: '#0B5FFF',
      contrastText: '#0B1220'
    },
    secondary: {
      main: '#2ED3B7',
      contrastText: '#0B1220'
    },
    background: {
      default: '#0B1220',
      paper: '#111A2E'
    },
    success: { main: '#4ADE80' },
    warning: { main: '#FBBF24' },
    error: { main: '#F87171' },
    text: {
      primary: '#E2E8F0',
      secondary: '#94A3B8'
    },
    divider: '#1E293B'
  },
  components: {
    MuiAppBar: {
      defaultProps: { elevation: 0, color: 'inherit' },
      styleOverrides: {
        root: {
          borderBottom: '1px solid #1E293B',
          backgroundColor: '#0F172A'
        }
      }
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: '1px solid #1E293B',
          backgroundImage: 'none'
        }
      }
    },
    MuiButton: {
      defaultProps: { disableElevation: true }
    }
  }
});

/**
 * Resolve the MUI theme for the given mode. Unknown modes fall back to light
 * so a corrupt localStorage value never crashes the app.
 *
 * @param {'light'|'dark'|undefined} mode
 * @returns {import('@mui/material/styles').Theme}
 */
export function getPartnerTheme(mode) {
  return mode === 'dark' ? partnerThemeDark : partnerTheme;
}

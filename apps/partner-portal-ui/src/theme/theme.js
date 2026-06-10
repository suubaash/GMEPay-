'use client';
import { createTheme } from '@mui/material/styles';

/**
 * Partner Portal theme — same GMEPay primary blue as admin-ui but a lighter,
 * airier background so partners feel they're in a self-service product, not
 * an operator console.
 */
export const partnerTheme = createTheme({
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

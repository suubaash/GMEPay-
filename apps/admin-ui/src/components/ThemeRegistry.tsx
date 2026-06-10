'use client';

import { CssBaseline, ThemeProvider } from '@mui/material';
import { theme } from '@/theme/theme';

/** Applies the GMEPay+ MUI theme + CSS baseline to the App Router tree. */
export default function ThemeRegistry({ children }: { children: React.ReactNode }) {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {children}
    </ThemeProvider>
  );
}

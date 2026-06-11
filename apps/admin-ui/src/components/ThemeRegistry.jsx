'use client';

import { useEffect, useMemo } from 'react';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { buildTheme } from '@/theme/theme';
import { useAppDispatch, useAppSelector } from '@/store';
import { hydrateMode, UI_MODE_KEY } from '@/store/uiSlice';

/**
 * Applies the GMEPay+ MUI theme + CSS baseline to the App Router tree.
 *
 * Consumes `state.ui.mode` to pick between the light and dark palettes
 * (see theme/theme.js#buildTheme). On first mount, hydrates the mode
 * from localStorage so the user's last preference is restored.
 */
export default function ThemeRegistry({ children }) {
  const dispatch = useAppDispatch();
  const mode = useAppSelector((s) => s.ui?.mode ?? 'light');

  // One-shot hydrate from localStorage. Skipped on the server (no window).
  useEffect(() => {
    if (typeof window === 'undefined') return;
    try {
      const stored = window.localStorage.getItem(UI_MODE_KEY);
      if (stored === 'dark' || stored === 'light') {
        dispatch(hydrateMode(stored));
      }
    } catch {
      /* ignore */
    }
  }, [dispatch]);

  const theme = useMemo(() => buildTheme(mode), [mode]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {children}
    </ThemeProvider>
  );
}

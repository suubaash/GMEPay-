'use client';

import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { Alert, Snackbar } from '@mui/material';
import type { AlertColor } from '@mui/material';

/**
 * App-wide snackbar / toast provider.
 *
 * Exposes a `useSnackbar()` hook with `success`, `error`, `info`, `warning`
 * helpers — modelled after notistack but built on plain MUI <Snackbar> so we
 * don't take on another dep. Only one toast is visible at a time; queueing is
 * intentionally omitted in the C2 polish — most operator flows are one-shot
 * (create partner, save rounding mode) so the simple model is sufficient.
 *
 * Wire it into apps/admin-ui/src/app/layout.tsx so every page can call the
 * hook without touching the JSX tree.
 */

interface SnackState {
  open: boolean;
  message: string;
  severity: AlertColor;
}

interface SnackbarApi {
  success: (msg: string) => void;
  error: (msg: string) => void;
  info: (msg: string) => void;
  warning: (msg: string) => void;
}

const SnackbarContext = createContext<SnackbarApi | null>(null);

const DEFAULT_AUTO_HIDE_MS = 5000;

export default function SnackbarProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<SnackState>({
    open: false,
    message: '',
    severity: 'info',
  });

  const show = useCallback((message: string, severity: AlertColor) => {
    setState({ open: true, message, severity });
  }, []);

  const api = useMemo<SnackbarApi>(
    () => ({
      success: (m: string) => show(m, 'success'),
      error: (m: string) => show(m, 'error'),
      info: (m: string) => show(m, 'info'),
      warning: (m: string) => show(m, 'warning'),
    }),
    [show],
  );

  const handleClose = useCallback(() => {
    setState((s) => ({ ...s, open: false }));
  }, []);

  return (
    <SnackbarContext.Provider value={api}>
      {children}
      <Snackbar
        open={state.open}
        autoHideDuration={DEFAULT_AUTO_HIDE_MS}
        onClose={handleClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          severity={state.severity}
          onClose={handleClose}
          variant="filled"
          sx={{ width: '100%' }}
        >
          {state.message}
        </Alert>
      </Snackbar>
    </SnackbarContext.Provider>
  );
}

/** Hook returning the app-wide snackbar API. Must be called inside the provider tree. */
export function useSnackbar(): SnackbarApi {
  const ctx = useContext(SnackbarContext);
  if (!ctx) {
    throw new Error('useSnackbar must be used inside <SnackbarProvider>');
  }
  return ctx;
}

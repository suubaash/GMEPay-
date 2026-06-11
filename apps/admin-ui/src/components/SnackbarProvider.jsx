'use client';

import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { Alert, Snackbar } from '@mui/material';

/**
 * App-wide snackbar / toast provider.
 *
 * Exposes a `useSnackbar()` hook with `success`, `error`, `info`, `warning`
 * helpers — modelled after notistack but built on plain MUI <Snackbar> so we
 * don't take on another dep. Only one toast is visible at a time; queueing is
 * intentionally omitted — most operator flows are one-shot (create partner,
 * save rounding mode) so the simple model is sufficient.
 */

const SnackbarContext = createContext(null);

const DEFAULT_AUTO_HIDE_MS = 5000;

export default function SnackbarProvider({ children }) {
  const [state, setState] = useState({
    open: false,
    message: '',
    severity: 'info',
  });

  const show = useCallback((message, severity) => {
    setState({ open: true, message, severity });
  }, []);

  const api = useMemo(
    () => ({
      success: (m) => show(m, 'success'),
      error: (m) => show(m, 'error'),
      info: (m) => show(m, 'info'),
      warning: (m) => show(m, 'warning'),
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
export function useSnackbar() {
  const ctx = useContext(SnackbarContext);
  if (!ctx) {
    throw new Error('useSnackbar must be used inside <SnackbarProvider>');
  }
  return ctx;
}

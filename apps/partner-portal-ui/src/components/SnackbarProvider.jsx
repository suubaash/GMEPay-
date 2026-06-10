'use client';
import * as React from 'react';
import Snackbar from '@mui/material/Snackbar';
import Alert from '@mui/material/Alert';

/**
 * App-wide toast/snackbar system.
 *
 * Wrap the tree once (in providers.jsx) and call useSnackbar() from any
 * client component to push a toast. Toasts auto-dismiss after the configured
 * duration; only one is visible at a time (newest replaces).
 *
 * @typedef {object} SnackbarApi
 * @property {(message:string, severity?:'error'|'success'|'info'|'warning', durationMs?:number) => void} showToast
 * @property {(message:string) => void} showError
 * @property {(message:string) => void} showSuccess
 * @property {(message:string) => void} showInfo
 */

const SnackbarContext = React.createContext(null);

export function SnackbarProvider({ children }) {
  const [toast, setToast] = React.useState(null);
  const idRef = React.useRef(0);

  const api = React.useMemo(
    () => ({
      showToast: (message, severity = 'info', durationMs = 4000) => {
        idRef.current += 1;
        setToast({ id: idRef.current, message, severity, duration: durationMs });
      },
      showError: (message) => {
        idRef.current += 1;
        setToast({ id: idRef.current, message, severity: 'error', duration: 6000 });
      },
      showSuccess: (message) => {
        idRef.current += 1;
        setToast({ id: idRef.current, message, severity: 'success', duration: 3000 });
      },
      showInfo: (message) => {
        idRef.current += 1;
        setToast({ id: idRef.current, message, severity: 'info', duration: 4000 });
      }
    }),
    []
  );

  const handleClose = (_, reason) => {
    if (reason === 'clickaway') return;
    setToast(null);
  };

  return (
    <SnackbarContext.Provider value={api}>
      {children}
      <Snackbar
        key={toast?.id ?? 'idle'}
        open={Boolean(toast)}
        autoHideDuration={toast?.duration ?? 4000}
        onClose={handleClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        {toast ? (
          <Alert
            onClose={() => setToast(null)}
            severity={toast.severity}
            variant="filled"
            sx={{ width: '100%' }}
            data-testid="snackbar-toast"
          >
            {toast.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </SnackbarContext.Provider>
  );
}

/**
 * Access the toast API. Safe to call outside the provider (returns no-ops)
 * so unit tests that don't mount the provider don't crash.
 *
 * @returns {SnackbarApi}
 */
export function useSnackbar() {
  const ctx = React.useContext(SnackbarContext);
  if (ctx) return ctx;
  return {
    showToast: () => undefined,
    showError: () => undefined,
    showSuccess: () => undefined,
    showInfo: () => undefined
  };
}

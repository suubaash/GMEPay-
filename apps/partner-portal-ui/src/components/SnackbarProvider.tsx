'use client';
import * as React from 'react';
import Snackbar from '@mui/material/Snackbar';
import Alert from '@mui/material/Alert';
import type { AlertColor } from '@mui/material/Alert';

interface Toast {
  id: number;
  message: string;
  severity: AlertColor;
  duration: number;
}

interface SnackbarApi {
  showToast: (message: string, severity?: AlertColor, durationMs?: number) => void;
  showError: (message: string) => void;
  showSuccess: (message: string) => void;
  showInfo: (message: string) => void;
}

const SnackbarContext = React.createContext<SnackbarApi | null>(null);

/**
 * App-wide toast/snackbar system.
 *
 * Wrap the tree once (in `providers.tsx`) and call `useSnackbar()` from any
 * client component to push a toast. Toasts auto-dismiss after the configured
 * duration; only one is visible at a time (newest replaces).
 */
export function SnackbarProvider({ children }: { children: React.ReactNode }) {
  const [toast, setToast] = React.useState<Toast | null>(null);
  const idRef = React.useRef(0);

  const api: SnackbarApi = React.useMemo(
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

  const handleClose = (_: unknown, reason?: string) => {
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
 */
export function useSnackbar(): SnackbarApi {
  const ctx = React.useContext(SnackbarContext);
  if (ctx) return ctx;
  return {
    showToast: () => undefined,
    showError: () => undefined,
    showSuccess: () => undefined,
    showInfo: () => undefined
  };
}

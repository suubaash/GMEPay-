'use client';

import { Alert, AlertTitle, Box, Button } from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';

export interface ErrorAlertProps {
  /** Error message to display. Falsy values render nothing. */
  message?: string | null;
  /** Optional retry callback — renders a "Retry" button when provided. */
  onRetry?: () => void;
  /** Optional title; defaults to "Something went wrong". */
  title?: string;
  /** Severity passed through to MUI <Alert>; defaults to "error". */
  severity?: 'error' | 'warning' | 'info';
}

/**
 * Reusable error banner with an optional retry button.
 *
 * Used by every page that fetches from the BFF — gives the operator a fast,
 * predictable way to recover from transient network blips without reloading
 * the SPA. Renders nothing when `message` is falsy so the call site can drop
 * it in unconditionally.
 */
export default function ErrorAlert({
  message,
  onRetry,
  title = 'Something went wrong',
  severity = 'error',
}: ErrorAlertProps) {
  if (!message) return null;
  return (
    <Alert
      severity={severity}
      sx={{ mb: 2 }}
      action={
        onRetry ? (
          <Button
            color="inherit"
            size="small"
            startIcon={<RefreshIcon />}
            onClick={onRetry}
          >
            Retry
          </Button>
        ) : null
      }
    >
      <AlertTitle>{title}</AlertTitle>
      <Box component="span" sx={{ wordBreak: 'break-word' }}>
        {message}
      </Box>
    </Alert>
  );
}

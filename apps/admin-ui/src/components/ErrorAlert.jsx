'use client';

import { Alert, AlertTitle, Box, Button } from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';

/**
 * ErrorAlert — reusable error banner with an optional retry button.
 *
 * Props:
 *   message:   string | null  (falsy = renders nothing)
 *   onRetry:   () => void     (renders Retry button when provided)
 *   title:     string         (default "Something went wrong")
 *   severity:  'error' | 'warning' | 'info'  (default 'error')
 */
export default function ErrorAlert({
  message,
  onRetry,
  title = 'Something went wrong',
  severity = 'error',
}) {
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

'use client';
import * as React from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';

/**
 * Standard error surface for failed API calls.
 *
 * @param {{ title?: string, message: string, onRetry?: () => void,
 *           severity?: 'error'|'warning'|'info' }} props
 */
export default function ErrorAlert({
  title = 'Something went wrong',
  message,
  onRetry,
  severity = 'error'
}) {
  return (
    <Alert
      severity={severity}
      role="alert"
      action={
        onRetry ? (
          <Button color="inherit" size="small" onClick={onRetry} data-testid="error-retry">
            Try again
          </Button>
        ) : undefined
      }
    >
      <AlertTitle>{title}</AlertTitle>
      <Stack spacing={0.5}>
        <span>{message}</span>
      </Stack>
    </Alert>
  );
}

'use client';
import * as React from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';

export interface ErrorAlertProps {
  /** Optional bold title; defaults to "Something went wrong". */
  title?: string;
  /** Friendly message to display. */
  message: string;
  /** Optional retry handler — shows a "Try again" button when provided. */
  onRetry?: () => void;
  /** Optional MUI severity override; defaults to "error". */
  severity?: 'error' | 'warning' | 'info';
}

/**
 * Standard error surface for failed API calls.
 *
 * Behaviour:
 *  - shows the supplied message in a MUI Alert (red by default),
 *  - if `onRetry` is provided, renders a small "Try again" action so the
 *    user can re-trigger the failed thunk without reloading the page.
 */
export default function ErrorAlert({
  title = 'Something went wrong',
  message,
  onRetry,
  severity = 'error'
}: ErrorAlertProps) {
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

'use client';

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
} from '@mui/material';

/**
 * ConfirmDialog — reusable MUI Dialog confirm prompt for destructive /
 * non-recoverable actions.
 *
 * Props:
 *   open:           boolean
 *   title:          string
 *   message:        string
 *   confirmLabel:   string  (default 'Confirm')
 *   cancelLabel:    string  (default 'Cancel')
 *   confirmColor:   'primary' | 'error' | 'warning'  (default 'primary')
 *   onConfirm:      () => void
 *   onCancel:       () => void
 *
 * Clicking the backdrop counts as Cancel.
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  confirmColor = 'primary',
  onConfirm,
  onCancel,
}) {
  return (
    <Dialog open={open} onClose={onCancel} aria-labelledby="confirm-dialog-title">
      <DialogTitle id="confirm-dialog-title">{title}</DialogTitle>
      <DialogContent>
        <DialogContentText>{message}</DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>{cancelLabel}</Button>
        <Button
          onClick={onConfirm}
          variant="contained"
          color={confirmColor}
          autoFocus
        >
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

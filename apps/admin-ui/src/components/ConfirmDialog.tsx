'use client';

import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
} from '@mui/material';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  /** Body text — render-safe string only. */
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Severity of the confirm button. Use "error" for destructive actions. */
  confirmColor?: 'primary' | 'error' | 'warning';
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Reusable MUI Dialog confirm prompt for destructive / non-recoverable actions
 * (e.g. deactivating a partner, force-closing a settlement batch).
 *
 * The component is fully controlled — the caller owns the `open` state and
 * the click handlers. Two buttons are wired:
 *   - Cancel  -> {@link onCancel}
 *   - Confirm -> {@link onConfirm}
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
}: ConfirmDialogProps) {
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

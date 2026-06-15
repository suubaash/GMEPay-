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
 * DeactivateConfirmDialog — confirm before deactivating an operator user.
 *
 * Surfaces the 4-eyes note: deactivation is irreversible without a second
 * operator re-enabling the account.
 *
 * Props:
 *   open:       boolean
 *   user:       UserSummary | null
 *   onConfirm:  () => void
 *   onCancel:   () => void
 */
export default function DeactivateConfirmDialog({ open, user, onConfirm, onCancel }) {
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      aria-labelledby="deactivate-confirm-title"
    >
      <DialogTitle id="deactivate-confirm-title">
        Deactivate {user?.name ?? 'user'}?
      </DialogTitle>
      <DialogContent>
        <DialogContentText>
          This will immediately revoke access for{' '}
          <strong>{user?.email ?? 'this user'}</strong>. They will not be able to
          log in until a second operator reactivates the account (4-eyes
          control). Are you sure?
        </DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          onClick={onConfirm}
          variant="contained"
          color="error"
          autoFocus
          aria-label="Confirm deactivation"
        >
          Deactivate
        </Button>
      </DialogActions>
    </Dialog>
  );
}

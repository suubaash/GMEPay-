'use client';

import { useState } from 'react';
import {
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  FormLabel,
  TextField,
  Typography,
} from '@mui/material';
import { ALL_ROLES } from './page';

/**
 * InviteUserDialog — collects email + initial roles then calls onSubmit.
 *
 * Validates:
 *   - email: required, must look like an email address
 *   - roles: at least one role must be selected
 *
 * Props:
 *   open:      boolean
 *   saving:    boolean
 *   onSubmit:  ({ email, roles }) => void
 *   onCancel:  () => void
 */
export default function InviteUserDialog({ open, saving, onSubmit, onCancel }) {
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState('');
  const [roles, setRoles] = useState([]);
  const [rolesError, setRolesError] = useState('');

  const reset = () => {
    setEmail('');
    setEmailError('');
    setRoles([]);
    setRolesError('');
  };

  const handleCancel = () => {
    reset();
    onCancel();
  };

  const toggleRole = (role) => {
    setRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role],
    );
    setRolesError('');
  };

  const validate = () => {
    let valid = true;
    if (!email.trim()) {
      setEmailError('Email is required');
      valid = false;
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setEmailError('Enter a valid email address');
      valid = false;
    } else if (email.trim().length > 254) {
      setEmailError('Email must be 254 characters or fewer');
      valid = false;
    } else {
      setEmailError('');
    }
    if (roles.length === 0) {
      setRolesError('Select at least one role');
      valid = false;
    } else {
      setRolesError('');
    }
    return valid;
  };

  const handleSubmit = () => {
    if (!validate()) return;
    onSubmit({ email: email.trim(), roles });
    reset();
  };

  return (
    <Dialog
      open={open}
      onClose={handleCancel}
      aria-labelledby="invite-user-dialog-title"
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle id="invite-user-dialog-title">Invite operator user</DialogTitle>
      <DialogContent>
        <TextField
          label="Email address"
          type="email"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            setEmailError('');
          }}
          error={!!emailError}
          helperText={emailError || ' '}
          fullWidth
          margin="normal"
          autoFocus
          inputProps={{ maxLength: 254, 'aria-label': 'Email address' }}
        />

        <FormControl
          component="fieldset"
          error={!!rolesError}
          sx={{ mt: 1, width: '100%' }}
        >
          <FormLabel component="legend">Initial roles</FormLabel>
          <FormGroup row>
            {ALL_ROLES.map((r) => (
              <FormControlLabel
                key={r}
                control={
                  <Checkbox
                    checked={roles.includes(r)}
                    onChange={() => toggleRole(r)}
                    inputProps={{ 'aria-label': r }}
                  />
                }
                label={r}
              />
            ))}
          </FormGroup>
          {rolesError && <FormHelperText>{rolesError}</FormHelperText>}
        </FormControl>

        <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
          The invitee will receive an email to set their password. A second
          operator must review access grants for ADMIN role.
        </Typography>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleCancel} disabled={saving}>
          Cancel
        </Button>
        <Button
          onClick={handleSubmit}
          variant="contained"
          disabled={saving}
          aria-label="Send invitation"
        >
          {saving ? 'Sending…' : 'Send invitation'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

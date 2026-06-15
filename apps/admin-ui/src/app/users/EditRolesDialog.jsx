'use client';

import { useEffect, useState } from 'react';
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
  Typography,
} from '@mui/material';
import { ALL_ROLES } from './page';

/**
 * EditRolesDialog — multi-select role editor for an existing operator user.
 *
 * Validates that at least one role is checked before submitting.
 *
 * Props:
 *   open:      boolean
 *   user:      UserSummary | null
 *   saving:    boolean
 *   onSubmit:  (roles: string[]) => void
 *   onCancel:  () => void
 */
export default function EditRolesDialog({ open, user, saving, onSubmit, onCancel }) {
  const [roles, setRoles] = useState([]);
  const [rolesError, setRolesError] = useState('');

  // Seed the checkbox state whenever a new user is opened
  useEffect(() => {
    if (user) {
      setRoles(Array.isArray(user.roles) ? [...user.roles] : []);
      setRolesError('');
    }
  }, [user]);

  const toggleRole = (role) => {
    setRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role],
    );
    setRolesError('');
  };

  const handleSubmit = () => {
    if (roles.length === 0) {
      setRolesError('Select at least one role');
      return;
    }
    onSubmit(roles);
  };

  const handleCancel = () => {
    setRolesError('');
    onCancel();
  };

  return (
    <Dialog
      open={open}
      onClose={handleCancel}
      aria-labelledby="edit-roles-dialog-title"
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle id="edit-roles-dialog-title">
        Edit roles{user ? ` — ${user.name}` : ''}
      </DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {user?.email}
        </Typography>

        <FormControl component="fieldset" error={!!rolesError} sx={{ width: '100%' }}>
          <FormLabel component="legend">Assigned roles</FormLabel>
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
          Role changes to ADMIN require 4-eyes review before taking effect.
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
          aria-label="Save roles"
        >
          {saving ? 'Saving…' : 'Save roles'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

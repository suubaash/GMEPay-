'use client';

import { useState } from 'react';
import {
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';

/**
 * CreateRoleDialog — modal for creating a new role.
 *
 * Props:
 *   open        : boolean
 *   onClose     : () => void
 *   onSubmit    : ({ name: string, basePermissions: string[] }) => void
 *   permissions : PermissionDef[]
 *   creating    : boolean
 */
export default function CreateRoleDialog({
  open,
  onClose,
  onSubmit,
  permissions,
  creating,
}) {
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState('');
  const [selected, setSelected] = useState(new Set());

  const handleNameChange = (e) => {
    setName(e.target.value);
    if (nameError) setNameError('');
  };

  const handleToggle = (perm) => {
    const next = new Set(selected);
    if (next.has(perm)) {
      next.delete(perm);
    } else {
      next.add(perm);
    }
    setSelected(next);
  };

  const validate = () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setNameError('Role name is required.');
      return false;
    }
    if (!/^[A-Z0-9_]+$/.test(trimmed)) {
      setNameError('Use only uppercase letters, digits and underscores (e.g. AUDIT_READ).');
      return false;
    }
    if (trimmed.length > 64) {
      setNameError('Role name must be 64 characters or fewer.');
      return false;
    }
    return true;
  };

  const handleSubmit = () => {
    if (!validate()) return;
    onSubmit({ name: name.trim(), basePermissions: Array.from(selected) });
  };

  const handleClose = () => {
    if (creating) return; // prevent close while in-flight
    setName('');
    setNameError('');
    setSelected(new Set());
    onClose();
  };

  // Group permissions by resource for a cleaner layout.
  const byResource = permissions.reduce((acc, p) => {
    acc[p.resource] = acc[p.resource] ?? [];
    acc[p.resource].push(p);
    return acc;
  }, {});

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      aria-labelledby="create-role-dialog-title"
    >
      <DialogTitle id="create-role-dialog-title">Create new role</DialogTitle>
      <DialogContent dividers>
        <TextField
          label="Role name"
          value={name}
          onChange={handleNameChange}
          error={!!nameError}
          helperText={nameError || 'Uppercase letters, digits and underscores only (e.g. AUDIT_READ).'}
          fullWidth
          required
          autoFocus
          inputProps={{ maxLength: 64, 'aria-label': 'Role name' }}
          sx={{ mb: 3 }}
        />

        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          Base permissions
        </Typography>

        {Object.entries(byResource).map(([resource, perms], idx) => (
          <Box key={resource}>
            {idx > 0 && <Divider sx={{ my: 1 }} />}
            <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase' }}>
              {resource}
            </Typography>
            <FormGroup>
              {perms.map((p) => (
                <FormControlLabel
                  key={p.permission}
                  control={
                    <Checkbox
                      checked={selected.has(p.permission)}
                      onChange={() => handleToggle(p.permission)}
                      size="small"
                      inputProps={{ 'aria-label': p.permission }}
                    />
                  }
                  label={
                    <Box>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                        {p.permission}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {p.description}
                      </Typography>
                    </Box>
                  }
                />
              ))}
            </FormGroup>
          </Box>
        ))}

        <FormHelperText sx={{ mt: 2 }}>
          Additional permissions can be granted via the matrix after creation.
          Role creation requires rbac.manage and is subject to 4-eyes approval.
        </FormHelperText>
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={creating}>
          Cancel
        </Button>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={handleSubmit}
          disabled={creating}
          aria-label="Create role"
        >
          {creating ? 'Creating…' : 'Create role'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

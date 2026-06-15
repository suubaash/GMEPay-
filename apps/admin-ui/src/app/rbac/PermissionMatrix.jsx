'use client';

import { useState, useMemo } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import SaveIcon from '@mui/icons-material/Save';
import EditIcon from '@mui/icons-material/Edit';
import CancelIcon from '@mui/icons-material/Cancel';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';

/**
 * PermissionMatrix — rows = permissions, columns = roles.
 * Each cell is a checkbox (editable when in edit mode).
 *
 * Props:
 *   roles       : RoleSummary[]
 *   permissions : PermissionDef[]
 *   loading     : boolean
 *   saving      : boolean
 *   error       : string | null
 *   saveError   : string | null
 *   onRetry     : () => void
 *   onSave      : (role: string, grants: string[]) => Promise<void>
 */
export default function PermissionMatrix({
  roles,
  permissions,
  loading,
  saving,
  error,
  saveError,
  onRetry,
  onSave,
}) {
  /**
   * Local draft state: Map<role, Set<permission>>
   * Initialised from the server data; modified as the user toggles checkboxes
   * in edit mode. Reset on cancel.
   */
  const [editMode, setEditMode] = useState(false);
  const [draft, setDraft] = useState(null); // null = not editing

  // Build a stable baseline from server data (memo so it only rerenders when
  // roles/permissions actually change, not on every render).
  const baseline = useMemo(() => {
    const map = {};
    roles.forEach((r) => {
      map[r.role] = new Set(r.permissions ?? []);
    });
    return map;
  }, [roles]);

  // The "live" grant sets: draft when editing, baseline otherwise.
  const grantSets = editMode && draft ? draft : baseline;

  const handleEnterEdit = () => {
    // Deep-copy the baseline sets into draft.
    const copy = {};
    roles.forEach((r) => {
      copy[r.role] = new Set(baseline[r.role] ?? []);
    });
    setDraft(copy);
    setEditMode(true);
  };

  const handleCancel = () => {
    setDraft(null);
    setEditMode(false);
  };

  const handleToggle = (role, permission) => {
    if (!editMode || !draft) return;
    const next = { ...draft };
    const set = new Set(next[role] ?? []);
    if (set.has(permission)) {
      set.delete(permission);
    } else {
      set.add(permission);
    }
    next[role] = set;
    setDraft(next);
  };

  /**
   * Detect which roles changed vs baseline, save them all, then exit edit mode.
   */
  const handleSave = async () => {
    if (!draft) return;
    const changedRoles = roles.filter((r) => {
      const base = baseline[r.role] ?? new Set();
      const current = draft[r.role] ?? new Set();
      if (base.size !== current.size) return true;
      for (const p of base) {
        if (!current.has(p)) return true;
      }
      return false;
    });
    // Save all changed roles in sequence (avoids concurrent writes).
    for (const r of changedRoles) {
      await onSave(r.role, Array.from(draft[r.role] ?? []));
    }
    setDraft(null);
    setEditMode(false);
  };

  if (loading && permissions.length === 0) {
    return <LoadingSkeleton variant="table" rows={9} />;
  }

  return (
    <Box>
      <Box
        sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}
      >
        <Typography variant="h2">Permission matrix</Typography>
        <Box sx={{ display: 'flex', gap: 1 }}>
          {editMode ? (
            <>
              <Button
                variant="outlined"
                startIcon={<CancelIcon />}
                onClick={handleCancel}
                disabled={saving}
              >
                Cancel
              </Button>
              <Button
                variant="contained"
                startIcon={<SaveIcon />}
                onClick={handleSave}
                disabled={saving}
                aria-label="Save permission changes"
              >
                {saving ? 'Saving…' : 'Save'}
              </Button>
            </>
          ) : (
            <Button
              variant="outlined"
              startIcon={<EditIcon />}
              onClick={handleEnterEdit}
              aria-label="Edit permissions"
            >
              Edit
            </Button>
          )}
        </Box>
      </Box>

      {editMode && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Role changes require 4-eyes approval — your edits will be submitted as a
          PROPOSED change request for a second operator to approve.
        </Alert>
      )}

      <ErrorAlert message={error} onRetry={onRetry} title="Could not load permissions" />
      <ErrorAlert message={saveError} title="Could not save permissions" />

      <TableContainer component={Paper} sx={{ overflowX: 'auto' }}>
        <Table
          aria-label="Permission matrix"
          size="small"
          sx={{ minWidth: 600 }}
        >
          <TableHead>
            <TableRow>
              <TableCell sx={{ minWidth: 220, fontWeight: 700 }}>Permission</TableCell>
              {roles.map((r) => (
                <TableCell
                  key={r.role}
                  align="center"
                  sx={{ fontWeight: 700, fontFamily: 'monospace', minWidth: 110 }}
                >
                  {r.role}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {permissions.map((perm) => (
              <TableRow key={perm.permission} hover>
                <TableCell>
                  <Tooltip title={perm.description ?? ''} placement="right">
                    <Box>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                        {perm.permission}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {perm.description}
                      </Typography>
                    </Box>
                  </Tooltip>
                </TableCell>
                {roles.map((r) => {
                  const granted = !!(grantSets[r.role] && grantSets[r.role].has(perm.permission));
                  return (
                    <TableCell key={r.role} align="center" padding="checkbox">
                      <Checkbox
                        checked={granted}
                        onChange={() => handleToggle(r.role, perm.permission)}
                        disabled={!editMode || saving}
                        inputProps={{
                          'aria-label': `${r.role} ${perm.permission}`,
                        }}
                        size="small"
                      />
                    </TableCell>
                  );
                })}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}

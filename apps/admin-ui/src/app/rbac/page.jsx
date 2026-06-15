'use client';

import { useCallback, useEffect, useState } from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchRbacData,
  saveRolePermissions,
  createNewRole,
  clearError,
  clearSaveError,
} from '@/store/rbacSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import RolesTable from './RolesTable';
import PermissionMatrix from './PermissionMatrix';
import CreateRoleDialog from './CreateRoleDialog';

/**
 * RBAC page (/rbac) — Roles & Permissions matrix.
 *
 * Layout
 * ------
 *   1. Roles summary table (role, description, user count).
 *   2. Permission matrix — rows = permissions, columns = roles, cells = checkboxes.
 *      "Edit" enters edit mode; "Save" dispatches saveRolePermissions for each
 *      changed role (4-eyes note is shown inline).
 *   3. "Create role" button opens a dialog with name validation + base-permission
 *      picker.
 *
 * Data source
 * -----------
 *   GET /v1/admin/rbac/roles        → roles list
 *   GET /v1/admin/rbac/permissions  → permission catalogue
 *   PUT /v1/admin/rbac/roles/{role}/permissions  → save grant set
 *   POST /v1/admin/rbac/roles       → create role
 *
 *   Both GET endpoints fall back to fixture data (see rbacApi.js) so the page
 *   is demoable while the BFF endpoint is absent.
 */
export default function RbacPage() {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const { roles, permissions, loading, saving, creating, error, saveError } =
    useAppSelector((s) => s.rbac);

  const [createDialogOpen, setCreateDialogOpen] = useState(false);

  const reload = useCallback(() => {
    dispatch(fetchRbacData());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  // Clear saveError from store when snackbar is shown.
  useEffect(() => {
    if (saveError) {
      snackbar.error(saveError);
      dispatch(clearSaveError());
    }
  }, [saveError, snackbar, dispatch]);

  const handleSave = useCallback(
    async (role, grants) => {
      try {
        await dispatch(saveRolePermissions({ role, grants })).unwrap();
        snackbar.success(`Permissions for ${role} submitted for approval.`);
      } catch (e) {
        // saveError is also set in the slice; snackbar shown via effect above.
      }
    },
    [dispatch, snackbar],
  );

  const handleCreateRole = useCallback(
    async ({ name, basePermissions }) => {
      try {
        await dispatch(createNewRole({ name, basePermissions })).unwrap();
        snackbar.success(`Role ${name} created successfully.`);
        setCreateDialogOpen(false);
      } catch (e) {
        // saveError is shown via effect.
      }
    },
    [dispatch, snackbar],
  );

  const handleClearError = useCallback(() => {
    dispatch(clearError());
  }, [dispatch]);

  return (
    <Box>
      {/* Page header */}
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Roles &amp; Permissions
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setCreateDialogOpen(true)}
          aria-label="Create new role"
        >
          Create role
        </Button>
      </Box>

      <Stack spacing={4}>
        {/* Roles summary */}
        <RolesTable
          roles={roles}
          loading={loading}
          error={error}
          onRetry={reload}
        />

        {/* Permission matrix */}
        <PermissionMatrix
          roles={roles}
          permissions={permissions}
          loading={loading}
          saving={saving}
          error={null}
          saveError={null}
          onRetry={reload}
          onSave={handleSave}
        />
      </Stack>

      {/* Create-role dialog */}
      <CreateRoleDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onSubmit={handleCreateRole}
        permissions={permissions}
        creating={creating}
      />
    </Box>
  );
}

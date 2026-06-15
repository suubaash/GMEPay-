'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  InputAdornment,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Toolbar,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import SearchIcon from '@mui/icons-material/Search';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  deactivateUser,
  fetchUsers,
  inviteUser,
  reactivateUser,
  updateUserRoles,
} from '@/store/usersSlice';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';
import InviteUserDialog from './InviteUserDialog';
import EditRolesDialog from './EditRolesDialog';
import DeactivateConfirmDialog from './DeactivateConfirmDialog';

/** All roles the auth-identity service recognises. */
export const ALL_ROLES = ['ADMIN', 'OPS', 'COMPLIANCE', 'FINANCE', 'READ_ONLY'];

/** Role → MUI Chip colour mapping. */
function roleColor(role) {
  switch (role) {
    case 'ADMIN':
      return 'error';
    case 'OPS':
      return 'primary';
    case 'COMPLIANCE':
      return 'warning';
    case 'FINANCE':
      return 'success';
    case 'READ_ONLY':
    default:
      return 'default';
  }
}

/** Status → MUI Chip colour mapping. */
function statusColor(status) {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'INVITED':
      return 'info';
    case 'DISABLED':
      return 'default';
    default:
      return 'default';
  }
}

/**
 * Format an ISO-8601 timestamp as KST (Asia/Seoul).
 * Returns '—' for null/undefined.
 */
function formatKst(iso) {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

/**
 * Operator User Management page — /users.
 *
 * Lists all operator (human) users with name, email, roles, status and last
 * login (KST).  Supports:
 *   - Text search (name / email)
 *   - Filter by role
 *   - Filter by status
 *   - Invite user (dialog)
 *   - Edit roles (dialog)
 *   - Deactivate / Reactivate (confirm dialog)
 *
 * Backed by GET /v1/admin/users via usersApi.listUsers().  When the backend
 * is absent the fixture list is shown with a warning banner.
 *
 * 4-eyes note: deactivation is a privileged action.  The confirm dialog
 * surfaces this so an operator does not accidentally lock a colleague out.
 */
export default function UsersPage() {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { items, loading, saving, error, fromFixture } = useAppSelector(
    (s) => s.users,
  );

  // ---- local UI state ----
  const [search, setSearch] = useState('');
  const [filterRole, setFilterRole] = useState('ALL');
  const [filterStatus, setFilterStatus] = useState('ALL');

  const [inviteOpen, setInviteOpen] = useState(false);
  const [editRolesTarget, setEditRolesTarget] = useState(null); // UserSummary | null
  const [deactivateTarget, setDeactivateTarget] = useState(null); // UserSummary | null

  // ---- fetch on mount ----
  const reload = useCallback(() => {
    dispatch(fetchUsers());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  // ---- derived rows ----
  const rows = Array.isArray(items) ? items : [];

  const filtered = rows.filter((u) => {
    if (filterRole !== 'ALL' && !u.roles?.includes(filterRole)) return false;
    if (filterStatus !== 'ALL' && u.status !== filterStatus) return false;
    if (search) {
      const q = search.toLowerCase();
      if (
        !u.name?.toLowerCase().includes(q) &&
        !u.email?.toLowerCase().includes(q)
      )
        return false;
    }
    return true;
  });

  // ---- action handlers ----
  const handleInviteSubmit = async ({ email, roles }) => {
    try {
      await dispatch(inviteUser({ email, roles })).unwrap();
      snackbar.success(`Invitation sent to ${email}`);
      setInviteOpen(false);
    } catch (e) {
      snackbar.error(`Invite failed: ${e?.message ?? 'unknown error'}`);
    }
  };

  const handleEditRolesSubmit = async (roles) => {
    if (!editRolesTarget) return;
    try {
      await dispatch(updateUserRoles({ id: editRolesTarget.id, roles })).unwrap();
      snackbar.success(`Roles updated for ${editRolesTarget.name}`);
      setEditRolesTarget(null);
    } catch (e) {
      snackbar.error(`Role update failed: ${e?.message ?? 'unknown error'}`);
    }
  };

  const handleDeactivateConfirm = async () => {
    if (!deactivateTarget) return;
    const user = deactivateTarget;
    setDeactivateTarget(null);
    try {
      await dispatch(deactivateUser(user.id)).unwrap();
      snackbar.success(`${user.name} has been deactivated`);
    } catch (e) {
      snackbar.error(`Deactivation failed: ${e?.message ?? 'unknown error'}`);
    }
  };

  const handleReactivate = async (user) => {
    try {
      await dispatch(reactivateUser(user.id)).unwrap();
      snackbar.success(`${user.name} has been reactivated`);
    } catch (e) {
      snackbar.error(`Reactivation failed: ${e?.message ?? 'unknown error'}`);
    }
  };

  // ---- render ----
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Users
        </Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setInviteOpen(true)}
          aria-label="Invite user"
        >
          Invite user
        </Button>
      </Box>

      {/* Demo fixture banner */}
      {fromFixture && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Backend unavailable — showing demo data. Actions will fail until the
          auth-identity service is deployed.
        </Alert>
      )}

      <ErrorAlert
        message={fromFixture ? null : error}
        onRetry={reload}
        title="Could not load users"
      />

      {/* ---- Filters toolbar ---- */}
      <Paper variant="outlined" sx={{ mb: 2 }}>
        <Toolbar sx={{ gap: 2, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            placeholder="Search name or email"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            }}
            sx={{ minWidth: 240, flexGrow: 1 }}
            inputProps={{ 'aria-label': 'Search users' }}
          />
          <Select
            size="small"
            value={filterRole}
            onChange={(e) => setFilterRole(e.target.value)}
            inputProps={{ 'aria-label': 'Filter by role' }}
            sx={{ minWidth: 140 }}
          >
            <MenuItem value="ALL">All roles</MenuItem>
            {ALL_ROLES.map((r) => (
              <MenuItem key={r} value={r}>
                {r}
              </MenuItem>
            ))}
          </Select>
          <Select
            size="small"
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value)}
            inputProps={{ 'aria-label': 'Filter by status' }}
            sx={{ minWidth: 140 }}
          >
            <MenuItem value="ALL">All statuses</MenuItem>
            <MenuItem value="ACTIVE">ACTIVE</MenuItem>
            <MenuItem value="INVITED">INVITED</MenuItem>
            <MenuItem value="DISABLED">DISABLED</MenuItem>
          </Select>
        </Toolbar>
      </Paper>

      {/* ---- Table ---- */}
      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No users yet"
            description='Click "Invite user" to add the first operator.'
            ctaLabel="Invite user"
            onCta={() => setInviteOpen(true)}
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Operator users">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Email</TableCell>
                <TableCell>Roles</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Last login (KST)</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 4 }}>
                    <Typography color="text.secondary">
                      No users match the current filters.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                filtered.map((user) => (
                  <TableRow key={user.id} hover>
                    <TableCell>{user.name ?? '—'}</TableCell>
                    <TableCell>{user.email ?? '—'}</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} flexWrap="wrap">
                        {(user.roles ?? []).map((r) => (
                          <Chip
                            key={r}
                            label={r}
                            size="small"
                            color={roleColor(r)}
                          />
                        ))}
                        {(!user.roles || user.roles.length === 0) && '—'}
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={user.status ?? '—'}
                        size="small"
                        color={statusColor(user.status)}
                      />
                    </TableCell>
                    <TableCell>{formatKst(user.lastLoginAt)}</TableCell>
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button
                          size="small"
                          variant="outlined"
                          onClick={() => setEditRolesTarget(user)}
                          aria-label={`Edit roles for ${user.name}`}
                          disabled={saving}
                        >
                          Edit roles
                        </Button>
                        {user.status === 'DISABLED' ? (
                          <Button
                            size="small"
                            variant="outlined"
                            color="success"
                            onClick={() => handleReactivate(user)}
                            aria-label={`Reactivate ${user.name}`}
                            disabled={saving}
                          >
                            Reactivate
                          </Button>
                        ) : (
                          <Button
                            size="small"
                            variant="outlined"
                            color="error"
                            onClick={() => setDeactivateTarget(user)}
                            aria-label={`Deactivate ${user.name}`}
                            disabled={saving || user.status === 'INVITED'}
                          >
                            Deactivate
                          </Button>
                        )}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* ---- Dialogs ---- */}
      <InviteUserDialog
        open={inviteOpen}
        saving={saving}
        onSubmit={handleInviteSubmit}
        onCancel={() => setInviteOpen(false)}
      />

      <EditRolesDialog
        open={!!editRolesTarget}
        user={editRolesTarget}
        saving={saving}
        onSubmit={handleEditRolesSubmit}
        onCancel={() => setEditRolesTarget(null)}
      />

      <DeactivateConfirmDialog
        open={!!deactivateTarget}
        user={deactivateTarget}
        onConfirm={handleDeactivateConfirm}
        onCancel={() => setDeactivateTarget(null)}
      />
    </Box>
  );
}

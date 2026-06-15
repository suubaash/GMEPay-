'use client';

import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import PeopleIcon from '@mui/icons-material/People';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import EmptyState from '@/components/EmptyState';
import ErrorAlert from '@/components/ErrorAlert';

/**
 * RolesTable — summary list of all roles with description and user count.
 *
 * Props:
 *   roles    : RoleSummary[]
 *   loading  : boolean
 *   error    : string | null
 *   onRetry  : () => void
 */
export default function RolesTable({ roles, loading, error, onRetry }) {
  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
        <PeopleIcon color="primary" />
        <Typography variant="h2">Roles</Typography>
      </Box>

      <ErrorAlert message={error} onRetry={onRetry} title="Could not load roles" />

      {loading && roles.length === 0 ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : !loading && roles.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No roles defined"
            description="Roles are provisioned via the BFF. Check that /v1/admin/rbac/roles is reachable."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Roles summary">
            <TableHead>
              <TableRow>
                <TableCell>Role</TableCell>
                <TableCell>Description</TableCell>
                <TableCell align="right">Users</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {roles.map((r) => (
                <TableRow key={r.role} hover>
                  <TableCell>
                    <Typography
                      variant="body2"
                      sx={{ fontWeight: 600, fontFamily: 'monospace' }}
                    >
                      {r.role}
                    </Typography>
                  </TableCell>
                  <TableCell>{r.description ?? '—'}</TableCell>
                  <TableCell align="right">{r.userCount ?? 0}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

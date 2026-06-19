'use client';

import { useCallback, useEffect } from 'react';
import {
  Box,
  Chip,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useAppDispatch, useAppSelector } from '@/store';
import { listSchemes } from '@/store/schemesSlice';

/**
 * QR scheme list page — GET /v1/admin/schemes.
 *
 * Each row is a SchemeSummary:
 *   { schemeId, name, country, currency, mode, status }
 * `status` from config-registry's catalog is "ACTIVE" | "PLANNED"
 * ("INACTIVE" kept for back-compat) — color-coded inline.
 */
function statusColor(status) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'PLANNED') return 'warning';
  if (status === 'INACTIVE') return 'default';
  return 'default';
}

export default function SchemesPage() {
  const dispatch = useAppDispatch();
  const { items, loading, error } = useAppSelector((s) => s.schemes);

  const reload = useCallback(() => {
    dispatch(listSchemes());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  const rows = Array.isArray(items) ? items : [];

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        QR Schemes
      </Typography>

      <ErrorAlert message={error} onRetry={reload} title="Could not load schemes" />

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No QR schemes registered"
            description="Schemes will appear here once config-registry is populated."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Scheme ID</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Country</TableCell>
                <TableCell>Currency</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((s) => (
                <TableRow key={s.schemeId} hover>
                  <TableCell>{s.schemeId}</TableCell>
                  <TableCell>{s.name ?? '—'}</TableCell>
                  <TableCell>{s.country ?? '—'}</TableCell>
                  <TableCell>{s.currency ?? '—'}</TableCell>
                  <TableCell>{s.mode ?? '—'}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={s.status ?? 'UNKNOWN'}
                      color={statusColor(s.status)}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

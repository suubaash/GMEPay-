'use client';

import { useCallback, useEffect } from 'react';
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
import StatusChip from '@/components/StatusChip';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useAppDispatch, useAppSelector } from '@/store';
import { listSchemes } from '@/store/schemesSlice';
import { Chip } from '@mui/material';

/**
 * QR scheme list page.
 *
 * Columns: schemeId, displayName, country, currency, mode, status.
 * Pulls from /v1/admin/schemes (BFF projection of config-registry's
 * scheme registry). Status is rendered via the shared StatusChip
 * (ACTIVE -> success, INACTIVE -> default).
 */
export default function SchemesPage() {
  const dispatch = useAppDispatch();
  const { items, loading, error } = useAppSelector((s) => s.schemes);

  const reload = useCallback(() => {
    dispatch(listSchemes());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        QR Schemes
      </Typography>

      <ErrorAlert message={error} onRetry={reload} title="Could not load schemes" />

      {loading && items.length === 0 ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : !loading && items.length === 0 && !error ? (
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
                <TableCell>Display name</TableCell>
                <TableCell>Country</TableCell>
                <TableCell>Currency</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((s) => (
                <TableRow key={s.schemeId} hover>
                  <TableCell>{s.schemeId}</TableCell>
                  <TableCell>{s.displayName}</TableCell>
                  <TableCell>{s.country ?? '—'}</TableCell>
                  <TableCell>{s.currency ?? '—'}</TableCell>
                  <TableCell>{s.mode ?? '—'}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={s.active ? 'ACTIVE' : 'INACTIVE'}
                      color={s.active ? 'success' : 'default'}
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

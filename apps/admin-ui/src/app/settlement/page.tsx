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
import { useRouter } from 'next/navigation';
import { useAppDispatch, useAppSelector } from '@/store';
import { listSettlements } from '@/store/settlementSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Recent settlement batches.
 *
 * Pulls from /v1/admin/settlement/recent (settlement-reconciliation via BFF).
 * Row click navigates to /settlement/{batchId} for the batch detail view.
 */
export default function SettlementPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { items, loading, error } = useAppSelector((s) => s.settlement);

  const reload = useCallback(() => {
    dispatch(listSettlements());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Settlement
      </Typography>

      <ErrorAlert message={error} onRetry={reload} title="Could not load settlements" />

      {loading && items.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && items.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No settlement batches yet"
            description="Batches appear here once settlement-reconciliation processes scheme files."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Batch ID</TableCell>
                <TableCell>Partner</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Total</TableCell>
                <TableCell>Created</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((b) => (
                <TableRow
                  key={b.batchId}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => router.push(`/settlement/${encodeURIComponent(b.batchId)}`)}
                >
                  <TableCell>{b.batchId}</TableCell>
                  <TableCell>{b.partnerId}</TableCell>
                  <TableCell>{b.status}</TableCell>
                  <TableCell>
                    <MoneyDisplay value={b.total} />
                  </TableCell>
                  <TableCell>{b.createdAt}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

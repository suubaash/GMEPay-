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
 * Recent settlement batches — GET /v1/admin/settlement/recent.
 *
 * Each row is a SettlementBatchSummary:
 *   { batchId, partnerId, settlementDate (LocalDate string),
 *     currency, amount (decimal string), status }
 *
 * Row click navigates to /settlement/{batchId}.
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

  const rows = Array.isArray(items) ? items : [];

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Settlement
      </Typography>

      <ErrorAlert message={error} onRetry={reload} title="Could not load settlements" />

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && rows.length === 0 && !error ? (
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
                <TableCell>Amount</TableCell>
                <TableCell>Settlement date</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((b) => (
                <TableRow
                  key={b.batchId}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => router.push(`/settlement/${encodeURIComponent(b.batchId)}`)}
                >
                  <TableCell>{b.batchId ?? '—'}</TableCell>
                  <TableCell>{b.partnerId ?? '—'}</TableCell>
                  <TableCell>{b.status ?? '—'}</TableCell>
                  <TableCell>
                    <MoneyDisplay amount={b.amount} currency={b.currency ?? ''} />
                  </TableCell>
                  <TableCell>{b.settlementDate ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

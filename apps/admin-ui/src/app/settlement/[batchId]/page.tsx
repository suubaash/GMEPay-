'use client';

import { useCallback, useEffect } from 'react';
import { useParams } from 'next/navigation';
import {
  Box,
  Card,
  CardContent,
  Chip,
  Grid2 as Grid,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { getSettlement } from '@/store/settlementSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Settlement batch detail page.
 *
 * GET /v1/admin/settlement/{batchId} -> renders:
 *   - Header card (batchId, partner, status, total, createdAt, closedAt).
 *   - Lines table with a "matched" chip (green tick) versus discrepancy
 *     rows (red label + reason).
 *
 * The "matched" chip is the key recon signal — anything other than green
 * means the scheme report row didn't reconcile against our internal record
 * and needs operator follow-up.
 */
export default function SettlementBatchDetailPage() {
  const params = useParams<{ batchId: string }>();
  const batchId = params?.batchId;
  const dispatch = useAppDispatch();
  const { details, detailLoading, error } = useAppSelector((s) => s.settlement);
  const batch = batchId ? details[batchId] : undefined;

  const reload = useCallback(() => {
    if (batchId) dispatch(getSettlement(batchId));
  }, [dispatch, batchId]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (error && !batch) {
    return <ErrorAlert message={error} onRetry={reload} title="Could not load settlement batch" />;
  }
  if (!batch) {
    return <LoadingSkeleton variant="page" />;
  }

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Batch {batch.batchId}
      </Typography>
      <ErrorAlert message={detailLoading ? null : error} onRetry={reload} />

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Partner
              </Typography>
              <Typography>{batch.partnerId}</Typography>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Status
              </Typography>
              <Typography>{batch.status}</Typography>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Total
              </Typography>
              <MoneyDisplay value={batch.total} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Created
              </Typography>
              <Typography>{batch.createdAt}</Typography>
            </Grid>
            {batch.closedAt ? (
              <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                <Typography variant="body2" color="text.secondary">
                  Closed
                </Typography>
                <Typography>{batch.closedAt}</Typography>
              </Grid>
            ) : null}
          </Grid>
        </CardContent>
      </Card>

      <Typography variant="h2" gutterBottom>
        Lines
      </Typography>
      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Line ID</TableCell>
              <TableCell>Transaction</TableCell>
              <TableCell>Amount</TableCell>
              <TableCell>Matched</TableCell>
              <TableCell>Reason</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {batch.lines.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} align="center">
                  <Typography color="text.secondary" sx={{ py: 2 }}>
                    No lines in this batch.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              batch.lines.map((line) => (
                <TableRow key={line.lineId} hover>
                  <TableCell>{line.lineId}</TableCell>
                  <TableCell>{line.txnId}</TableCell>
                  <TableCell>
                    <MoneyDisplay value={line.amount} />
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={line.matched ? 'MATCHED' : 'UNMATCHED'}
                      color={line.matched ? 'success' : 'error'}
                    />
                  </TableCell>
                  <TableCell>{line.reason ?? '—'}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}

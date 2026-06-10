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
 * GET /v1/admin/settlement/{batchId} -> SettlementBatchDetail
 *   { batch: SettlementBatchSummary, lines: SettlementLine[] }
 *
 * SettlementBatchSummary: { batchId, partnerId, settlementDate, currency, amount, status }
 * SettlementLine:         { txnRef, amount, currency, matched }
 */
export default function SettlementBatchDetailPage() {
  const params = useParams();
  const batchId = params?.batchId;
  const dispatch = useAppDispatch();
  const { details, detailLoading, error } = useAppSelector((s) => s.settlement);
  const detail = batchId ? details[batchId] : undefined;

  const reload = useCallback(() => {
    if (batchId) dispatch(getSettlement(batchId));
  }, [dispatch, batchId]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (error && !detail) {
    return <ErrorAlert message={error} onRetry={reload} title="Could not load settlement batch" />;
  }
  if (!detail) {
    return <LoadingSkeleton variant="page" />;
  }

  const batch = detail.batch ?? {};
  const lines = Array.isArray(detail.lines) ? detail.lines : [];
  const batchCurrency = batch.currency ?? '';

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Batch {batch.batchId ?? batchId}
      </Typography>
      <ErrorAlert message={detailLoading ? null : error} onRetry={reload} />

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Partner
              </Typography>
              <Typography>{batch.partnerId ?? '—'}</Typography>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Status
              </Typography>
              <Typography>{batch.status ?? '—'}</Typography>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Amount
              </Typography>
              <MoneyDisplay amount={batch.amount} currency={batchCurrency} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Settlement date
              </Typography>
              <Typography>{batch.settlementDate ?? '—'}</Typography>
            </Grid>
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
              <TableCell>Transaction Ref</TableCell>
              <TableCell>Amount</TableCell>
              <TableCell>Currency</TableCell>
              <TableCell>Matched</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {lines.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} align="center">
                  <Typography color="text.secondary" sx={{ py: 2 }}>
                    No lines in this batch.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              lines.map((line, idx) => (
                <TableRow key={line.txnRef ?? idx} hover>
                  <TableCell>{line.txnRef ?? '—'}</TableCell>
                  <TableCell>
                    <MoneyDisplay
                      amount={line.amount}
                      currency={line.currency ?? batchCurrency}
                      withCurrency={false}
                    />
                  </TableCell>
                  <TableCell>{line.currency ?? '—'}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={line.matched ? 'MATCHED' : 'UNMATCHED'}
                      color={line.matched ? 'success' : 'error'}
                    />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}

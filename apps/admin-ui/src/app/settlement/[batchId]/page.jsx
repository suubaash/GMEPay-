'use client';

import { useCallback, useEffect } from 'react';
import { useParams } from 'next/navigation';
import {
  Alert,
  AlertTitle,
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
  Tooltip,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchTransmissionChannel, getSettlement } from '@/store/settlementSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import Breadcrumbs from '@/components/Breadcrumbs';
import SettlementTransmissionBoard from '@/components/SettlementTransmissionBoard';
import {
  isTransmitted,
  settlementLifecycleMeta,
  transmissionReasonFor,
  transmissionStateMeta,
} from '@/api/settlementStatus';

/**
 * Settlement batch detail page.
 *
 * GET /v1/admin/settlement/{batchId} -> SettlementBatchDetail
 *   { batch: SettlementBatchSummary, lines: SettlementLine[], matchedCount, openCount }
 *
 * SettlementBatchSummary: { batchId, partnerId, settlementDate, currency, amount, status,
 *                           transmissionState, transmissionReason, transmittedAt }
 * SettlementLine:         { txnRef, amount, currency, matched }
 *
 * GAP T4-5 — this page used to show a single "Status" field holding `batch.status`, which
 * an operator reads as "sent". It is not: `status` is the reconciliation LIFECYCLE, and
 * `transmissionState` is the only field that says whether a file left the platform. Both
 * are now shown as separate facts, and the batch-level banner (driven by
 * `GET /v1/admin/settlement/transmission-channel`, not hardcoded) states plainly that
 * nothing has been transmitted and why. Success styling is reserved for a genuine
 * `TRANSMITTED`.
 */
export default function SettlementBatchDetailPage() {
  const params = useParams();
  const batchId = params?.batchId;
  const dispatch = useAppDispatch();
  const { details, channel, detailLoading, error } = useAppSelector((s) => s.settlement);
  const detail = batchId ? details[batchId] : undefined;

  const reload = useCallback(() => {
    if (batchId) dispatch(getSettlement(batchId));
  }, [dispatch, batchId]);

  useEffect(() => {
    reload();
    dispatch(fetchTransmissionChannel());
  }, [dispatch, reload]);

  if (error && !detail) {
    return <ErrorAlert message={error} onRetry={reload} title="Could not load settlement batch" />;
  }
  if (!detail) {
    return <LoadingSkeleton variant="page" />;
  }

  const batch = detail.batch ?? {};
  const lines = Array.isArray(detail.lines) ? detail.lines : [];
  const batchCurrency = batch.currency ?? '';
  const lifecycle = settlementLifecycleMeta(batch.status);
  const transmission = transmissionStateMeta(batch.transmissionState);
  const transmissionReason = transmissionReasonFor(batch);
  const sent = isTransmitted(batch.transmissionState);

  return (
    <Box>
      <Breadcrumbs
        crumbs={[
          { label: 'Settlement', href: '/settlement' },
          { label: batch.batchId ?? batchId ?? '' },
        ]}
      />
      <Typography variant="h1" gutterBottom>
        Batch {batch.batchId ?? batchId}
      </Typography>
      <ErrorAlert message={detailLoading ? null : error} onRetry={reload} />

      {/* Transmission truth. Absent from this page before T4-5, which is exactly how a
          RECONCILED batch came to read as "sent to the scheme". */}
      {!sent && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="not-transmitted-banner">
          <AlertTitle>This settlement file has not been transmitted to the scheme</AlertTitle>
          {`Its reconciliation lifecycle status is ${lifecycle.status}, which describes how far `
            + 'reconciliation got — not whether GMEPay+ sent anything. '}
          {transmissionReason}
          <Box sx={{ mt: 1 }}>
            <SettlementTransmissionBoard channel={channel} />
          </Box>
        </Alert>
      )}

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
                Lifecycle status
              </Typography>
              <Tooltip title={lifecycle.description}>
                <Chip
                  size="small"
                  variant="outlined"
                  label={lifecycle.label}
                  color={lifecycle.color}
                  aria-label={`Lifecycle status ${lifecycle.status}`}
                />
              </Tooltip>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Sent to scheme
              </Typography>
              <Tooltip title={transmissionReason || transmission.description}>
                <Chip
                  size="small"
                  label={transmission.label}
                  color={transmission.color}
                  variant={transmission.transmitted ? 'filled' : 'outlined'}
                  aria-label={`Transmission state ${transmission.state}`}
                />
              </Tooltip>
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
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Transmitted at
              </Typography>
              {/* Only shown as a time when the state agrees it was sent. A timestamp on a
                  batch that is not TRANSMITTED is not evidence of anything, so it is not
                  displayed as one. */}
              <Typography data-testid="transmitted-at">
                {sent ? (batch.transmittedAt ?? '—') : 'never transmitted'}
              </Typography>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Matched lines
              </Typography>
              <Typography>{detail.matchedCount ?? '—'}</Typography>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <Typography variant="body2" color="text.secondary">
                Open lines
              </Typography>
              <Typography>{detail.openCount ?? '—'}</Typography>
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
                    {/* Line-level MATCHED is a reconciliation fact about the scheme's
                        confirmation, not a transmission fact — it is legitimately green,
                        and the banner above keeps it from reading as delivery. */}
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

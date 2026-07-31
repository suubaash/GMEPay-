'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
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
import SearchIcon from '@mui/icons-material/Search';
import { useRouter } from 'next/navigation';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchTransmissionChannel, listSettlementBatches } from '@/store/settlementSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import DateRangePicker from '@/components/DateRangePicker';
import SettlementTransmissionBoard from '@/components/SettlementTransmissionBoard';
import {
  notTransmittedSummary,
  settlementLifecycleMeta,
  transmissionReasonFor,
  transmissionStateMeta,
} from '@/api/settlementStatus';

/**
 * Settlement batches over a business-date window — GET /v1/admin/settlement/batches.
 *
 * Each row is a SettlementBatchSummary:
 *   { batchId, partnerId, settlementDate (LocalDate string),
 *     currency, amount (decimal string), status,
 *     transmissionState, transmissionReason, transmittedAt }
 *
 * GAP T4-5 — this page used to render one "Status" column holding `b.status`, so a batch
 * reading `RECONCILED` was taken as "sent to the scheme" when in fact **nothing has ever
 * been transmitted** (the only transport writes to a local directory; real SFTP to
 * ZeroPay/KFTC is an external gate). Two independent facts now get two columns:
 *
 *   - **Lifecycle** — how far reconciliation got. Never a success colour, not even
 *     `RECONCILED`, because this column is not the answer to "did we send it?".
 *   - **Sent to scheme** — the only column that answers that, from `transmissionState`.
 *     Success colour lives here and nowhere else.
 *
 * Wording/colour for both comes from `@/api/settlementStatus`, and the standing banner is
 * driven by `GET /v1/admin/settlement/transmission-channel` rather than hardcoded, so it
 * self-removes if a real channel is ever configured.
 *
 * The window previously did not exist at all (`/settlement/recent` has no date parameters).
 * It defaults to the last 30 days, matching what upstream anchors an unbounded window to.
 *
 * Row click navigates to /settlement/{batchId}.
 */

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoISO(days) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

/** The one column that may be green, and only when the backend said TRANSMITTED. */
function TransmissionCell({ row }) {
  const meta = transmissionStateMeta(row?.transmissionState);
  const reason = transmissionReasonFor(row);
  const tip = reason && reason !== meta.description
    ? `${meta.description} — ${reason}`
    : meta.description;
  return (
    <Tooltip title={tip}>
      <Chip
        size="small"
        label={meta.label}
        color={meta.color}
        variant={meta.transmitted ? 'filled' : 'outlined'}
        aria-label={`Transmission state ${meta.state}`}
      />
    </Tooltip>
  );
}

export default function SettlementPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { items, channel, loading, error } = useAppSelector((s) => s.settlement);

  const [from, setFrom] = useState(daysAgoISO(30));
  const [to, setTo] = useState(todayISO());
  // The window actually fetched, so the empty state can name it rather than the pending
  // picker values.
  const [applied, setApplied] = useState({ from: daysAgoISO(30), to: todayISO() });

  const load = useCallback(
    (window) => {
      setApplied(window);
      dispatch(listSettlementBatches({ from: window.from, to: window.to, limit: 0 }));
    },
    [dispatch],
  );

  const reload = useCallback(() => {
    load({ from, to });
  }, [load, from, to]);

  useEffect(() => {
    load({ from: daysAgoISO(30), to: todayISO() });
    // The channel board is read once: it is deployment configuration, not per-window data.
    dispatch(fetchTransmissionChannel());
  }, [dispatch, load]);

  const rows = Array.isArray(items) ? items : [];

  // Stated once, from what the rows actually say — the banner disappears the moment any
  // batch reports a real TRANSMITTED.
  const notTransmitted = notTransmittedSummary(rows);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Settlement
      </Typography>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Grid container spacing={2} alignItems="center">
            <Grid size={{ xs: 12, md: 'auto' }}>
              <DateRangePicker
                from={from}
                to={to}
                max={todayISO()}
                fromLabel="Settlement date from"
                toLabel="Settlement date to"
                onChange={(r) => {
                  setFrom(r.from);
                  setTo(r.to);
                }}
              />
            </Grid>
            <Grid size={{ xs: 12, md: 'auto' }}>
              <Button
                variant="contained"
                startIcon={<SearchIcon />}
                onClick={reload}
                disabled={loading}
              >
                Fetch
              </Button>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <ErrorAlert message={error} onRetry={reload} title="Could not load settlements" />

      {/* Transmission truth — a lifecycle status is not a delivery receipt. */}
      {notTransmitted && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="not-transmitted-banner">
          <AlertTitle>No settlement file has been transmitted to a scheme</AlertTitle>
          {notTransmitted}
          <Box sx={{ mt: 1 }}>
            <SettlementTransmissionBoard channel={channel} />
          </Box>
        </Alert>
      )}

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No settlement batches in this window"
            description={
              `Nothing was booked between ${applied.from} and ${applied.to}. Batches appear here `
              + 'once settlement-reconciliation processes scheme files — widen the window or pick '
              + 'another period.'
            }
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Settlement batches">
            <TableHead>
              <TableRow>
                <TableCell>Batch ID</TableCell>
                <TableCell>Partner</TableCell>
                <TableCell>Lifecycle</TableCell>
                <TableCell>Sent to scheme</TableCell>
                <TableCell>Amount</TableCell>
                <TableCell>Settlement date</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((b) => {
                const lifecycle = settlementLifecycleMeta(b.status);
                return (
                  <TableRow
                    key={b.batchId}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => router.push(`/settlement/${encodeURIComponent(b.batchId)}`)}
                  >
                    <TableCell>{b.batchId ?? '—'}</TableCell>
                    <TableCell>{b.partnerId ?? '—'}</TableCell>
                    <TableCell>
                      <Tooltip title={lifecycle.description}>
                        <Chip
                          size="small"
                          variant="outlined"
                          label={lifecycle.label}
                          color={lifecycle.color}
                          aria-label={`Lifecycle status ${lifecycle.status}`}
                        />
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      <TransmissionCell row={b} />
                    </TableCell>
                    <TableCell>
                      <MoneyDisplay amount={b.amount} currency={b.currency ?? ''} />
                    </TableCell>
                    <TableCell>{b.settlementDate ?? '—'}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

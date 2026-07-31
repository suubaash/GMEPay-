'use client';
import * as React from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Card,
  CardContent,
  Chip,
  Divider,
  Grid,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import { fetchSettlements, isTransmitted } from '@/store/settlementsSlice';
import { currentPartnerId } from '@/api/client';
import DateRangePicker from '@/components/DateRangePicker';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Settlement statement page (gap T4-5) — READ-ONLY.
 *
 * Reads GET /v1/portal/{partnerId}/settlements, i.e. the SETTLED RECORD out of
 * settlement-reconciliation's persisted settlement_batches / settlement_lines. Distinct from
 * /statement, which downloads the partner's TRANSACTION CSV.
 *
 * <h3>The one thing this page must never do</h3>
 * It must not let a partner read "GMEPay+ reconciled this" as "GMEPay+ sent the instruction to the
 * scheme". Those are separate facts on separate fields, and today only the first is ever true:
 * no settlement transmission channel is configured in any environment (scheme SFTP credentials plus a
 * certification run are externally gated). So:
 *   - the lifecycle `status` chip is never coloured as a success on its own;
 *   - a separate "Sent to scheme" column states No/Yes from `transmissionState`, with the reason on
 *     hover — it is NEVER derived from `status`;
 *   - a standing banner says plainly that nothing on the page has been transmitted whenever
 *     `transmissionChannel.live` is false, and an absent/unknown board is treated as not-live
 *     rather than optimistically as available.
 *
 * Money is a decimal string end to end (MoneyDisplay); it is never Number()-cast.
 *
 * There are no actions on this page. Partner self-serve settlement writes (dispute, adjust, request
 * payout) are an open product decision (gap T1-5); a disabled-looking button would be the same
 * "capability appears available but is not" defect this gap exists to remove.
 */

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoISO(days) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

/** Only the literal TRANSMITTED counts as sent — UNKNOWN and absent never do. */
function sentLabel(batch) {
  if (isTransmitted(batch)) return 'Yes';
  return 'No';
}

export default function SettlementsPage() {
  const dispatch = useDispatch();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s) => s.settlements);

  const [range, setRange] = React.useState({ from: daysAgoISO(30), to: todayISO() });

  const load = React.useCallback(() => {
    dispatch(
      fetchSettlements({
        partnerId,
        from: range.from || undefined,
        to: range.to || undefined,
        includeLines: true
      })
    );
  }, [dispatch, partnerId, range.from, range.to]);

  React.useEffect(() => {
    load();
  }, [load]);

  const entries = Array.isArray(data?.entries) ? data.entries : [];
  const channel = data?.transmissionChannel ?? null;
  // An absent board is NOT evidence of a live channel. Treat unknown as not-live.
  const channelLive = channel?.live === true;
  const currency = data?.currency ?? '';
  const loading = status === 'loading';

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Settlement statement
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Your settled position, taken from the settlement batches GMEPay+ booked and reconciled. This
        is the settled record — not a projection of today&apos;s transactions.
      </Typography>

      {!channelLive && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="no-transmission-banner">
          <AlertTitle>Nothing on this page has been sent to the scheme</AlertTitle>
          GMEPay+ has booked and reconciled these settlements, but no settlement file has been
          transmitted: there is no settlement transmission channel configured.
          {channel?.reason ? ` ${channel.reason}` : ''}
        </Alert>
      )}

      <Stack direction="row" spacing={2} sx={{ mb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
        <DateRangePicker
          from={range.from}
          to={range.to}
          onChange={setRange}
          max={todayISO()}
          disabled={loading}
        />
      </Stack>

      {error && (
        <ErrorAlert
          message={error}
          onRetry={load}
          title="Could not load the settlement statement"
        />
      )}

      {loading && entries.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : (
        <>
          <Grid container spacing={2} sx={{ mb: 3 }}>
            <Grid item xs={12} sm={6} md={3}>
              <Card variant="outlined">
                <CardContent>
                  <Typography variant="overline" color="text.secondary">
                    Net settled
                  </Typography>
                  <Typography variant="h5" data-testid="total-net">
                    <MoneyDisplay amount={data?.netSettlementAmount ?? '0'} currency={currency} />
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Card variant="outlined">
                <CardContent>
                  <Typography variant="overline" color="text.secondary">
                    Paid out
                  </Typography>
                  <Typography variant="h5">
                    <MoneyDisplay amount={data?.paymentAmount ?? '0'} currency={currency} />
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Card variant="outlined">
                <CardContent>
                  <Typography variant="overline" color="text.secondary">
                    Refunds netted back
                  </Typography>
                  <Typography variant="h5">
                    <MoneyDisplay amount={data?.clawbackAmount ?? '0'} currency={currency} />
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid item xs={12} sm={6} md={3}>
              <Card variant="outlined">
                <CardContent>
                  <Typography variant="overline" color="text.secondary">
                    Sent to scheme
                  </Typography>
                  {/* Stated, never implied: the count of entries actually transmitted. */}
                  <Typography variant="h5" data-testid="transmitted-count">
                    {`${data?.transmittedEntryCount ?? 0} of ${entries.length}`}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          </Grid>

          {entries.length === 0 && !error ? (
            <Paper variant="outlined">
              <EmptyState
                title="No settlement batches in this period"
                message="Batches appear here once a settlement window for your transactions has been booked."
              />
            </Paper>
          ) : (
            <TableContainer component={Paper} sx={{ overflowX: 'auto' }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Batch</TableCell>
                    <TableCell>Settlement date</TableCell>
                    <TableCell align="right">Net</TableCell>
                    <TableCell align="right">Paid</TableCell>
                    <TableCell align="right">Refunds</TableCell>
                    <TableCell align="right">Lines</TableCell>
                    <TableCell>Reconciliation</TableCell>
                    <TableCell>Sent to scheme</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {entries.map((e) => {
                    const b = e?.batch ?? {};
                    const sent = isTransmitted(b);
                    return (
                      <TableRow key={b.batchId ?? Math.random()} hover>
                        <TableCell>{b.batchId ?? '—'}</TableCell>
                        <TableCell>{b.settlementDate ?? '—'}</TableCell>
                        <TableCell align="right">
                          <MoneyDisplay
                            amount={e?.netSettlementAmount ?? '0'}
                            currency={b.currency ?? currency}
                          />
                        </TableCell>
                        <TableCell align="right">
                          <MoneyDisplay
                            amount={e?.paymentAmount ?? '0'}
                            currency={b.currency ?? currency}
                          />
                        </TableCell>
                        <TableCell align="right">
                          <MoneyDisplay
                            amount={e?.clawbackAmount ?? '0'}
                            currency={b.currency ?? currency}
                          />
                        </TableCell>
                        <TableCell align="right">
                          {e?.openLineCount > 0
                            ? `${e.lineCount} (${e.openLineCount} open)`
                            : (e?.lineCount ?? 0)}
                        </TableCell>
                        <TableCell>
                          {/* Neutral outlined chip: reconciliation status is NOT a claim that the
                              money was instructed to the scheme, so it is never a green tick. */}
                          <StatusChip status={b.status ?? 'UNKNOWN'} />
                        </TableCell>
                        <TableCell>
                          <Tooltip
                            title={
                              sent
                                ? `Transmitted at ${b.transmittedAt ?? 'an unrecorded time'}`
                                : (b.transmissionReason ??
                                  'This settlement file has not been transmitted to the scheme.')
                            }
                          >
                            <Chip
                              size="small"
                              variant="outlined"
                              color={sent ? 'success' : 'default'}
                              label={sentLabel(b)}
                              data-testid={`sent-${b.batchId ?? 'row'}`}
                            />
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}

          <Divider sx={{ my: 3 }} />
          <Typography variant="caption" color="text.secondary">
            &quot;Reconciliation&quot; is how far GMEPay+ got in matching this batch against the
            scheme&apos;s confirmation file. &quot;Sent to scheme&quot; is a separate fact: whether
            the settlement instruction itself left GMEPay+. A batch can be reconciled without having
            been sent.
          </Typography>
        </>
      )}
    </Box>
  );
}

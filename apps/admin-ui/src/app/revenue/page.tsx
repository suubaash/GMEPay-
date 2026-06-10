'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  FormControl,
  Grid2 as Grid,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import { useAppDispatch, useAppSelector } from '@/store';
import { getBreakdown, getSummary } from '@/store/revenueSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import DateRangePicker from '@/components/DateRangePicker';

/** ISO YYYY-MM-DD for today, in the user's local timezone. */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** ISO YYYY-MM-DD for 30 days ago. */
function thirtyDaysAgo(): string {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return d.toISOString().slice(0, 10);
}

type Dimension = 'partner' | 'scheme' | 'currency';

/**
 * Revenue summary + breakdown page.
 *
 * Top: date-range picker + dimension dropdown (partner | scheme | currency).
 * Middle: 3 summary cards from /v1/admin/revenue/summary
 *   - total revenue
 *   - rounding gain
 *   - rounding loss
 * Bottom: breakdown table from /v1/admin/revenue/breakdown grouped by the
 * selected dimension.
 *
 * Rounding gain/loss is surfaced separately because the per-partner rounding
 * residual is the central money-integrity invariant (MONEY_CONVENTION.md):
 * sum of gains − sum of losses should equal the partner-side rounding delta.
 */
export default function RevenuePage() {
  const dispatch = useAppDispatch();
  const { summary, breakdown, loading, breakdownLoading, error } = useAppSelector(
    (s) => s.revenue,
  );
  const [from, setFrom] = useState<string>(thirtyDaysAgo());
  const [to, setTo] = useState<string>(today());
  const [dimension, setDimension] = useState<Dimension>('partner');

  const reload = useCallback(() => {
    const range = { from, to };
    dispatch(getSummary(range));
    dispatch(getBreakdown({ ...range, dimension }));
  }, [dispatch, from, to, dimension]);

  useEffect(() => {
    reload();
    // intentionally only on mount; user-driven refresh is via the Apply button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Revenue
      </Typography>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Grid container spacing={2} alignItems="center">
            <Grid size={{ xs: 12, md: 'auto' }}>
              <DateRangePicker
                from={from}
                to={to}
                max={today()}
                onChange={(r) => {
                  setFrom(r.from);
                  setTo(r.to);
                }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <FormControl size="small" fullWidth>
                <InputLabel id="dimension-label">Group by</InputLabel>
                <Select
                  labelId="dimension-label"
                  label="Group by"
                  value={dimension}
                  onChange={(e) => setDimension(e.target.value as Dimension)}
                >
                  <MenuItem value="partner">Partner</MenuItem>
                  <MenuItem value="scheme">Scheme</MenuItem>
                  <MenuItem value="currency">Currency</MenuItem>
                </Select>
              </FormControl>
            </Grid>
            <Grid size={{ xs: 12, md: 'auto' }}>
              <Button variant="contained" startIcon={<SearchIcon />} onClick={reload}>
                Apply
              </Button>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <ErrorAlert message={error} onRetry={reload} title="Could not load revenue" />

      {loading && !summary ? (
        <Grid container spacing={2}>
          {[0, 1, 2].map((i) => (
            <Grid key={i} size={{ xs: 12, md: 4 }}>
              <LoadingSkeleton variant="card" />
            </Grid>
          ))}
        </Grid>
      ) : summary ? (
        <Grid container spacing={2} sx={{ mb: 3 }}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Total revenue ({summary.periodStart} → {summary.periodEnd})
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay value={summary.totalRevenue} />
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Rounding gain (REVENUE_ROUNDING credits)
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay value={summary.totalRoundingGain} negativeRed={false} />
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Rounding loss (REVENUE_ROUNDING debits)
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay value={summary.totalRoundingLoss} />
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      ) : null}

      <Typography variant="h2" gutterBottom>
        Breakdown by {dimension}
      </Typography>

      {breakdownLoading && !breakdown ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : !breakdown || breakdown.rows.length === 0 ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No revenue rows in this period"
            description="Try widening the date range or selecting a different dimension."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>{dimension}</TableCell>
                <TableCell>Revenue</TableCell>
                <TableCell>Rounding gain</TableCell>
                <TableCell>Rounding loss</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {breakdown.rows.map((row) => (
                <TableRow key={`${row.dimension}:${row.key}`} hover>
                  <TableCell>{row.key}</TableCell>
                  <TableCell>
                    <MoneyDisplay value={row.revenue} />
                  </TableCell>
                  <TableCell>
                    <MoneyDisplay value={row.roundingGain} negativeRed={false} />
                  </TableCell>
                  <TableCell>
                    <MoneyDisplay value={row.roundingLoss} />
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

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

/**
 * Revenue summary + breakdown page.
 *
 * GET /v1/admin/revenue/summary?from&to -> RevenueSummary
 *   { date, totalRevenueUsd, feeRevenueUsd, marginRevenueUsd }
 *
 * GET /v1/admin/revenue/breakdown?from&to -> RevenueBreakdown
 *   { byPartner:{string -> string},
 *     byScheme: {string -> string},
 *     byCurrency:{string -> string} }
 *   Map values are decimal strings (USD totals).
 *
 * The Dimension dropdown picks which map (byPartner/byScheme/byCurrency)
 * to display in the breakdown table — the request itself is identical.
 */
function today() {
  return new Date().toISOString().slice(0, 10);
}

function thirtyDaysAgo() {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return d.toISOString().slice(0, 10);
}

function pickDimension(breakdown, dimension) {
  if (!breakdown) return {};
  if (dimension === 'partner') return breakdown.byPartner ?? {};
  if (dimension === 'scheme') return breakdown.byScheme ?? {};
  return breakdown.byCurrency ?? {};
}

export default function RevenuePage() {
  const dispatch = useAppDispatch();
  const { summary, breakdown, loading, breakdownLoading, error } = useAppSelector(
    (s) => s.revenue,
  );
  const [from, setFrom] = useState(thirtyDaysAgo());
  const [to, setTo] = useState(today());
  const [dimension, setDimension] = useState('partner');

  const reload = useCallback(() => {
    const range = { from, to };
    dispatch(getSummary(range));
    dispatch(getBreakdown(range));
  }, [dispatch, from, to]);

  useEffect(() => {
    reload();
    // initial mount only — user-driven refresh is via the Apply button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const dimensionMap = pickDimension(breakdown, dimension);
  const dimensionEntries = Object.entries(dimensionMap);

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
                  onChange={(e) => setDimension(e.target.value)}
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
                  Total revenue ({from} → {to})
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay
                    amount={String(summary.totalRevenueUsd ?? '0')}
                    currency="USD"
                  />
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Fee revenue
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay
                    amount={String(summary.feeRevenueUsd ?? '0')}
                    currency="USD"
                    negativeRed={false}
                  />
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Margin revenue
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay
                    amount={String(summary.marginRevenueUsd ?? '0')}
                    currency="USD"
                  />
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
      ) : !breakdown || dimensionEntries.length === 0 ? (
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
                <TableCell>Revenue (USD)</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {dimensionEntries.map(([key, value]) => (
                <TableRow key={`${dimension}:${key}`} hover>
                  <TableCell>{key}</TableCell>
                  <TableCell>
                    <MoneyDisplay amount={String(value)} currency="USD" />
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

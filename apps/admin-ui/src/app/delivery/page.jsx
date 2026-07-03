'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid2 as Grid,
  LinearProgress,
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
import { adminApi } from '@/api/client';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import DateRangePicker from '@/components/DateRangePicker';

/**
 * Delivery — a product-delivery dashboard answering one question in plain terms:
 * "are payments actually succeeding, and if not, why?"
 *
 * Reads GET /v1/admin/delivery/overview?from&to (see adminApi.getDeliveryOverview).
 * Everything here is descriptive stats — success rate, decline reasons, and how
 * long each partner took to make their first live payment (activation).
 */

function today() {
  return new Date().toISOString().slice(0, 10);
}

function thirtyDaysAgo() {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return d.toISOString().slice(0, 10);
}

/** Format an ISO instant/date for display; graceful on missing/bad input. */
function fmtDate(iso) {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(
      new Date(iso),
    );
  } catch {
    return String(iso);
  }
}

/** Round a percentage to one decimal, tolerant of null/undefined. */
function pct(n) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—';
  return `${Number(n).toFixed(1)}%`;
}

/** MUI palette color name for a success-rate band (green/amber/red). */
function rateColor(n) {
  const v = Number(n);
  if (Number.isNaN(v)) return 'text.secondary';
  if (v >= 95) return 'success.main';
  if (v >= 80) return 'warning.main';
  return 'error.main';
}

export default function DeliveryPage() {
  const [from, setFrom] = useState(thirtyDaysAgo());
  const [to, setTo] = useState(today());
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    // The BFF wants ISO instants; widen the day range to cover the full window.
    const range = { from: `${from}T00:00:00Z`, to: `${to}T23:59:59Z` };
    adminApi
      .getDeliveryOverview(range)
      .then((res) => setData(res))
      .catch((e) =>
        setError(
          e && e.message
            ? e.message
            : 'Couldn’t load delivery metrics — is the fleet running?',
        ),
      )
      .finally(() => setLoading(false));
  }, [from, to]);

  useEffect(() => {
    reload();
    // initial mount only — user-driven refresh is via the Apply button.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const overall = data?.successRate?.overall;
  const byPartner = data?.successRate?.byPartner ?? [];
  const byCorridor = data?.successRate?.byCorridor ?? [];
  const declineReasons = data?.declineReasons ?? [];
  const activation = data?.activation ?? [];

  const maxCorridorTotal = byCorridor.reduce(
    (m, c) => Math.max(m, Number(c.total) || 0),
    0,
  );
  const maxReasonCount = declineReasons.reduce(
    (m, r) => Math.max(m, Number(r.count) || 0),
    0,
  );
  const sortedReasons = [...declineReasons].sort(
    (a, b) => (Number(b.count) || 0) - (Number(a.count) || 0),
  );

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Delivery
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
        Are payments actually going through? This page shows how often payments
        succeed, why they fail, and how long each partner took to go live.
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
            <Grid size={{ xs: 12, md: 'auto' }}>
              <Button
                variant="contained"
                startIcon={<SearchIcon />}
                onClick={reload}
              >
                Apply
              </Button>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <ErrorAlert
        message={error}
        onRetry={reload}
        title="Couldn’t load delivery metrics — is the fleet running?"
      />

      {loading && !data ? (
        <LoadingSkeleton variant="page" />
      ) : !data ? (
        !error ? (
          <Paper variant="outlined">
            <EmptyState
              heading="No delivery data yet"
              description="Pick a date range and press Apply."
            />
          </Paper>
        ) : null
      ) : (
        <>
          {/* ---- Top line: overall success rate + counts ---- */}
          <Grid container spacing={2} sx={{ mb: 3 }}>
            <Grid size={{ xs: 12, md: 6 }}>
              <Card>
                <CardContent>
                  <Typography variant="body2" color="text.secondary">
                    Overall success rate ({from} → {to})
                  </Typography>
                  <Typography
                    variant="h1"
                    component="div"
                    sx={{ color: rateColor(overall?.successRatePct), mt: 1 }}
                    aria-label="overall-success-rate"
                  >
                    {pct(overall?.successRatePct)}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    of {(overall?.total ?? 0).toLocaleString()} payments
                    attempted
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid size={{ xs: 12, sm: 4, md: 2 }}>
              <Card>
                <CardContent>
                  <Typography variant="body2" color="text.secondary">
                    Attempted
                  </Typography>
                  <Typography variant="h3" component="div">
                    {(overall?.total ?? 0).toLocaleString()}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid size={{ xs: 12, sm: 4, md: 2 }}>
              <Card>
                <CardContent>
                  <Typography variant="body2" color="text.secondary">
                    Succeeded
                  </Typography>
                  <Typography
                    variant="h3"
                    component="div"
                    sx={{ color: 'success.main' }}
                  >
                    {(overall?.approved ?? 0).toLocaleString()}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid size={{ xs: 12, sm: 4, md: 2 }}>
              <Card>
                <CardContent>
                  <Typography variant="body2" color="text.secondary">
                    Failed
                  </Typography>
                  <Typography
                    variant="h3"
                    component="div"
                    sx={{ color: 'error.main' }}
                  >
                    {(overall?.declined ?? 0).toLocaleString()}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
          </Grid>

          {/* ---- Success rate by corridor (bar chart) ---- */}
          <Typography variant="h2" gutterBottom>
            Success rate by corridor
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Each bar is the share of payments that succeeded on that route. Bar
            width is scaled to how many payments the corridor handled.
          </Typography>
          {byCorridor.length === 0 ? (
            <Paper variant="outlined" sx={{ mb: 3 }}>
              <EmptyState
                heading="No corridor activity in this period"
                description="Try widening the date range."
              />
            </Paper>
          ) : (
            <Card sx={{ mb: 3 }} aria-label="success-rate-by-corridor">
              <CardContent>
                {byCorridor.map((c) => {
                  const widthPct =
                    maxCorridorTotal > 0
                      ? Math.max(6, ((Number(c.total) || 0) / maxCorridorTotal) * 100)
                      : 6;
                  return (
                    <Box key={c.corridor} sx={{ mb: 2 }}>
                      <Box
                        sx={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          mb: 0.5,
                        }}
                      >
                        <Typography variant="body2">{c.corridor}</Typography>
                        <Typography
                          variant="body2"
                          sx={{ color: rateColor(c.successRatePct), fontWeight: 600 }}
                        >
                          {pct(c.successRatePct)}
                          <Typography
                            component="span"
                            variant="caption"
                            color="text.secondary"
                            sx={{ ml: 1 }}
                          >
                            ({(c.approved ?? 0).toLocaleString()}/
                            {(c.total ?? 0).toLocaleString()})
                          </Typography>
                        </Typography>
                      </Box>
                      <Tooltip
                        title={`${(c.total ?? 0).toLocaleString()} payments · ${(
                          c.declined ?? 0
                        ).toLocaleString()} failed`}
                      >
                        <Box sx={{ width: `${widthPct}%`, minWidth: 40 }}>
                          <LinearProgress
                            variant="determinate"
                            value={Math.min(100, Number(c.successRatePct) || 0)}
                            color={
                              Number(c.successRatePct) >= 95
                                ? 'success'
                                : Number(c.successRatePct) >= 80
                                  ? 'warning'
                                  : 'error'
                            }
                            sx={{ height: 14, borderRadius: 1 }}
                          />
                        </Box>
                      </Tooltip>
                    </Box>
                  );
                })}
              </CardContent>
            </Card>
          )}

          {/* ---- Success rate by partner (table) ---- */}
          <Typography variant="h2" gutterBottom>
            Success rate by partner
          </Typography>
          {byPartner.length === 0 ? (
            <Paper variant="outlined" sx={{ mb: 3 }}>
              <EmptyState heading="No partner activity in this period" />
            </Paper>
          ) : (
            <TableContainer component={Paper} sx={{ mb: 3 }}>
              <Table aria-label="success rate by partner">
                <TableHead>
                  <TableRow>
                    <TableCell>Partner</TableCell>
                    <TableCell align="right">Attempted</TableCell>
                    <TableCell align="right">Succeeded</TableCell>
                    <TableCell align="right">Failed</TableCell>
                    <TableCell align="right">Success&nbsp;%</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {byPartner.map((p) => (
                    <TableRow key={p.partner} hover>
                      <TableCell>{p.partner}</TableCell>
                      <TableCell align="right">
                        {(p.total ?? 0).toLocaleString()}
                      </TableCell>
                      <TableCell align="right">
                        {(p.approved ?? 0).toLocaleString()}
                      </TableCell>
                      <TableCell align="right">
                        {(p.declined ?? 0).toLocaleString()}
                      </TableCell>
                      <TableCell
                        align="right"
                        sx={{ color: rateColor(p.successRatePct), fontWeight: 600 }}
                      >
                        {pct(p.successRatePct)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}

          {/* ---- Decline reasons (why payments fail) ---- */}
          <Typography variant="h2" gutterBottom>
            Why payments fail
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            The most common reasons payments were declined, most frequent first.
          </Typography>
          {sortedReasons.length === 0 ? (
            <Paper variant="outlined" sx={{ mb: 3 }}>
              <EmptyState
                heading="No declines in this period"
                description="Every attempted payment succeeded."
              />
            </Paper>
          ) : (
            <Card sx={{ mb: 3 }} aria-label="decline-reasons">
              <CardContent>
                {sortedReasons.map((r) => {
                  const widthPct =
                    maxReasonCount > 0
                      ? Math.max(6, ((Number(r.count) || 0) / maxReasonCount) * 100)
                      : 6;
                  return (
                    <Box key={r.reason} sx={{ mb: 1.5 }}>
                      <Box
                        sx={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          mb: 0.5,
                        }}
                      >
                        <Typography variant="body2">{r.reason}</Typography>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {(r.count ?? 0).toLocaleString()}
                        </Typography>
                      </Box>
                      <Box sx={{ width: `${widthPct}%`, minWidth: 40 }}>
                        <LinearProgress
                          variant="determinate"
                          value={100}
                          color="error"
                          sx={{ height: 12, borderRadius: 1 }}
                        />
                      </Box>
                    </Box>
                  );
                })}
              </CardContent>
            </Card>
          )}

          {/* ---- Activation (time to first live payment) ---- */}
          <Typography variant="h2" gutterBottom>
            Partner activation
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            How long each partner took from onboarding to their first successful
            payment. Partners with no payment yet are flagged as pending.
          </Typography>
          {activation.length === 0 ? (
            <Paper variant="outlined">
              <EmptyState heading="No partners onboarded in this period" />
            </Paper>
          ) : (
            <TableContainer component={Paper}>
              <Table aria-label="partner activation">
                <TableHead>
                  <TableRow>
                    <TableCell>Partner</TableCell>
                    <TableCell>Onboarded</TableCell>
                    <TableCell>First payment</TableCell>
                    <TableCell align="right">Time to activate</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {activation.map((a) => {
                    const hours = Number(a.activationHours);
                    const timeToActivate =
                      a.status === 'pending' || Number.isNaN(hours)
                        ? '—'
                        : hours < 48
                          ? `${hours.toFixed(1)} h`
                          : `${(hours / 24).toFixed(1)} d`;
                    return (
                      <TableRow key={a.partner} hover>
                        <TableCell>{a.partner}</TableCell>
                        <TableCell>{fmtDate(a.onboardedAt)}</TableCell>
                        <TableCell>{fmtDate(a.firstApprovedAt)}</TableCell>
                        <TableCell align="right">{timeToActivate}</TableCell>
                        <TableCell>
                          {a.status === 'activated' ? (
                            <Chip size="small" color="success" label="Activated" />
                          ) : (
                            <Chip
                              size="small"
                              color="warning"
                              label="Pending — no payment yet"
                            />
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </>
      )}
    </Box>
  );
}

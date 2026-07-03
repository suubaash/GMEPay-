'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid2 as Grid,
  MenuItem,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import DownloadIcon from '@mui/icons-material/Download';
import { adminApi } from '@/api/client';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import DateRangePicker from '@/components/DateRangePicker';

/**
 * Scheme Statements — a per-scheme reconciliation statement. GME staff pick a
 * QR scheme + date range and see everything GME recorded for that scheme in the
 * period: per-currency totals (the reconciliation headline) plus the line-item
 * transactions. Export the whole thing as CSV to hand to the scheme partner.
 *
 * Reads GET /v1/admin/schemes/{schemeId}/statement?from&to&page&size
 * (adminApi.getSchemeStatement). `gross` and `amount` are decimal STRINGS on
 * the wire — never Number()-cast for display (docs/MONEY_CONVENTION.md).
 *
 * The scheme picker is populated from GET /v1/admin/schemes (adminApi.listSchemes)
 * when reachable; if that fails or is empty we fall back to a couple of known
 * scheme ids (ZEROPAY, NEPAL) so the page is still usable.
 */

const FALLBACK_SCHEMES = ['ZEROPAY', 'NEPAL'];

function today() {
  return new Date().toISOString().slice(0, 10);
}

function thirtyDaysAgo() {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return d.toISOString().slice(0, 10);
}

/** Format an ISO instant for display; graceful on missing/bad input. */
function fmtTime(iso) {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(iso));
  } catch {
    return String(iso);
  }
}

/** MUI palette color name for a transaction status chip. */
function statusColor(status) {
  const s = String(status ?? '').toUpperCase();
  if (s === 'APPROVED' || s === 'SETTLED') return 'success';
  if (s === 'FAILED' || s === 'CANCELLED' || s === 'DECLINED') return 'error';
  if (s === 'PENDING' || s === 'CREATED' || s === 'QUOTED') return 'warning';
  return 'default';
}

/** Escape one CSV field: wrap in quotes and double any embedded quotes. */
function csvField(v) {
  const s = v === undefined || v === null ? '' : String(v);
  return `"${s.replace(/"/g, '""')}"`;
}

/**
 * Build the reconciliation CSV: a totals block (per currency) followed by the
 * line-item statement. Money is emitted as the raw decimal string, never cast.
 */
function buildStatementCsv(schemeId, from, to, data) {
  const totals = data?.totals ?? [];
  const items = data?.items ?? [];
  const lines = [];
  lines.push(['Scheme statement', schemeId].map(csvField).join(','));
  lines.push(['Window from', from, 'to', to].map(csvField).join(','));
  lines.push('');
  lines.push(['Totals'].map(csvField).join(','));
  lines.push(['Currency', 'Count', 'Gross'].map(csvField).join(','));
  for (const t of totals) {
    lines.push([t.currency, t.count, t.gross].map(csvField).join(','));
  }
  lines.push('');
  lines.push(['Transactions'].map(csvField).join(','));
  lines.push(
    ['Time', 'Txn ref', 'Merchant', 'Partner', 'Amount', 'Currency', 'Status']
      .map(csvField)
      .join(','),
  );
  for (const it of items) {
    lines.push(
      [
        it.occurredAt,
        it.txnRef,
        it.merchantId,
        it.partnerId,
        it.amount,
        it.currency,
        it.status,
      ]
        .map(csvField)
        .join(','),
    );
  }
  return lines.join('\r\n');
}

/** Trigger a client-side CSV download — no new dependency. */
function triggerCsvDownload(csvText, filename) {
  const blob = new Blob([csvText], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export default function SchemeStatementsPage() {
  const [schemeOptions, setSchemeOptions] = useState(FALLBACK_SCHEMES);
  const [schemeId, setSchemeId] = useState(FALLBACK_SCHEMES[0]);
  const [from, setFrom] = useState(thirtyDaysAgo());
  const [to, setTo] = useState(today());
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Populate the scheme picker from the platform's scheme catalog when
  // reachable; keep the known-id fallback otherwise.
  useEffect(() => {
    let cancelled = false;
    adminApi
      .listSchemes()
      .then((res) => {
        if (cancelled) return;
        const ids = (Array.isArray(res) ? res : [])
          .map((s) => s?.schemeId)
          .filter(Boolean);
        if (ids.length > 0) {
          setSchemeOptions(ids);
          setSchemeId((cur) => (ids.includes(cur) ? cur : ids[0]));
        }
      })
      .catch(() => {
        /* keep FALLBACK_SCHEMES */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const load = useCallback(
    (pageArg, sizeArg) => {
      if (!schemeId) return;
      setLoading(true);
      setError(null);
      const params = {
        schemeId,
        from: `${from}T00:00:00Z`,
        to: `${to}T23:59:59Z`,
        page: pageArg ?? page,
        size: sizeArg ?? size,
      };
      adminApi
        .getSchemeStatement(params)
        .then((res) => setData(res))
        .catch((e) =>
          setError(
            e && e.message
              ? e.message
              : 'Couldn’t load the scheme statement — is the fleet running?',
          ),
        )
        .finally(() => setLoading(false));
    },
    [schemeId, from, to, page, size],
  );

  // A new fetch always resets to the first page.
  const applyFetch = () => {
    setPage(0);
    load(0, size);
  };

  const onPageChange = (_e, next) => {
    setPage(next);
    load(next, size);
  };
  const onSizeChange = (e) => {
    const next = parseInt(e.target.value, 10);
    setSize(next);
    setPage(0);
    load(0, next);
  };

  const onExport = () => {
    if (!data) return;
    const csv = buildStatementCsv(schemeId, from, to, data);
    triggerCsvDownload(csv, `scheme-statement-${schemeId}-${from}_${to}.csv`);
  };

  const totals = data?.totals ?? [];
  const items = data?.items ?? [];
  const hasResult = !!data;

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Scheme Statements
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
        Everything GME recorded for a QR scheme in a period — hand this to the
        scheme for reconciliation.
      </Typography>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Grid container spacing={2} alignItems="center">
            <Grid size={{ xs: 12, md: 'auto' }}>
              <TextField
                select
                size="small"
                label="QR scheme"
                value={schemeId}
                onChange={(e) => setSchemeId(e.target.value)}
                sx={{ minWidth: 180 }}
                inputProps={{ 'aria-label': 'scheme select' }}
              >
                {schemeOptions.map((id) => (
                  <MenuItem key={id} value={id}>
                    {id}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
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
                onClick={applyFetch}
              >
                Fetch
              </Button>
            </Grid>
            <Grid size={{ xs: 12, md: 'auto' }}>
              <Button
                variant="outlined"
                startIcon={<DownloadIcon />}
                onClick={onExport}
                disabled={!hasResult}
                aria-label="export csv"
              >
                Export CSV
              </Button>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <ErrorAlert
        message={error}
        onRetry={applyFetch}
        title="Couldn’t load the scheme statement — is the fleet running?"
      />

      {loading && !data ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !hasResult ? (
        !error ? (
          <Paper variant="outlined">
            <EmptyState
              heading="Pick a scheme and date range"
              description="Choose a QR scheme and press Fetch to build its statement."
            />
          </Paper>
        ) : null
      ) : (
        <>
          {/* ---- Per-currency totals: the reconciliation headline ---- */}
          <Typography variant="h2" gutterBottom>
            Totals
          </Typography>
          {totals.length === 0 ? (
            <Paper variant="outlined" sx={{ mb: 3 }}>
              <EmptyState heading="No totals for this scheme in range" />
            </Paper>
          ) : (
            <Grid container spacing={2} sx={{ mb: 3 }} aria-label="scheme-totals">
              {totals.map((t) => (
                <Grid key={t.currency} size={{ xs: 12, sm: 6, md: 3 }}>
                  <Card>
                    <CardContent>
                      <Typography variant="body2" color="text.secondary">
                        {t.currency}
                      </Typography>
                      <Typography variant="h3" component="div">
                        {t.gross} {t.currency}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        {(t.count ?? 0).toLocaleString()} transactions
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>
              ))}
            </Grid>
          )}

          {/* ---- Line-item statement ---- */}
          <Typography variant="h2" gutterBottom>
            Transactions
          </Typography>
          {items.length === 0 ? (
            <Paper variant="outlined">
              <EmptyState
                heading="No transactions for this scheme in range"
                description="Try a different scheme or widen the date range."
              />
            </Paper>
          ) : (
            <TableContainer component={Paper}>
              <Table aria-label="scheme statement">
                <TableHead>
                  <TableRow>
                    <TableCell>Time</TableCell>
                    <TableCell>Txn ref</TableCell>
                    <TableCell>Merchant</TableCell>
                    <TableCell>Partner</TableCell>
                    <TableCell align="right">Amount</TableCell>
                    <TableCell>Currency</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((it) => (
                    <TableRow key={it.txnRef} hover>
                      <TableCell>{fmtTime(it.occurredAt)}</TableCell>
                      <TableCell sx={{ fontFamily: 'monospace' }}>
                        {it.txnRef ?? '—'}
                      </TableCell>
                      <TableCell>{it.merchantId ?? '—'}</TableCell>
                      <TableCell>{it.partnerId ?? '—'}</TableCell>
                      <TableCell align="right">{it.amount}</TableCell>
                      <TableCell>{it.currency}</TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={it.status ?? 'UNKNOWN'}
                          color={statusColor(it.status)}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <TablePagination
                component="div"
                count={data?.total ?? 0}
                page={data?.page ?? page}
                onPageChange={onPageChange}
                rowsPerPage={data?.size ?? size}
                onRowsPerPageChange={onSizeChange}
                rowsPerPageOptions={[10, 25, 50, 100]}
              />
            </TableContainer>
          )}
        </>
      )}
    </Box>
  );
}

'use client';

import { Fragment, useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Collapse,
  Grid2 as Grid,
  IconButton,
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
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CancelIcon from '@mui/icons-material/Cancel';
import { adminApi } from '@/api/client';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import DateRangePicker from '@/components/DateRangePicker';

/**
 * Journal — the posted double-entry ledger. Every money movement in GMEPay+
 * posts one balanced journal (Σ debits == Σ credits, per currency). This page
 * lists those journals newest-first and lets you expand any one to see its
 * ledger lines laid out the classic way: Debit on the left, Credit on the
 * right. Search by the money-movement / transaction reference.
 *
 * Reads GET /v1/admin/journals?from&to&reference&page&size (adminApi.getJournals).
 * `amount` is a decimal STRING on the wire — never Number()-cast for display;
 * we only Number()-cast when summing for the balanced check.
 */

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

/**
 * Per-currency debit/credit totals for one journal. Returns
 *   { balanced:boolean, byCurrency:[{ currency, debit, credit }] }
 * balanced === Σ DR == Σ CR for every currency present.
 */
function summarize(lines) {
  const map = new Map();
  for (const l of lines ?? []) {
    const ccy = l.currency ?? '—';
    const cur = map.get(ccy) ?? { currency: ccy, debit: 0, credit: 0 };
    const amt = Number(l.amount) || 0;
    if (l.side === 'DR') cur.debit += amt;
    else if (l.side === 'CR') cur.credit += amt;
    map.set(ccy, cur);
  }
  const byCurrency = [...map.values()];
  const balanced =
    byCurrency.length > 0 &&
    byCurrency.every((c) => Math.abs(c.debit - c.credit) < 0.005);
  return { balanced, byCurrency };
}

/** One journal row + its collapsible ledger view. */
function JournalRow({ entry }) {
  const [open, setOpen] = useState(false);
  const lines = entry.lines ?? [];
  const { balanced, byCurrency } = summarize(lines);
  const debits = lines.filter((l) => l.side === 'DR');
  const credits = lines.filter((l) => l.side === 'CR');

  return (
    <Fragment>
      <TableRow hover sx={{ '& > *': { borderBottom: 'unset' } }}>
        <TableCell padding="checkbox">
          <IconButton
            size="small"
            aria-label={open ? 'collapse journal' : 'expand journal'}
            onClick={() => setOpen((o) => !o)}
          >
            {open ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
          </IconButton>
        </TableCell>
        <TableCell>{fmtTime(entry.createdAt)}</TableCell>
        <TableCell sx={{ fontFamily: 'monospace' }}>
          {entry.reference ?? '—'}
        </TableCell>
        <TableCell align="right">{lines.length}</TableCell>
        <TableCell>
          {balanced ? (
            <Chip
              size="small"
              color="success"
              icon={<CheckCircleIcon />}
              label="Balanced"
              aria-label="balanced"
            />
          ) : (
            <Chip
              size="small"
              color="error"
              icon={<CancelIcon />}
              label="Out of balance"
              aria-label="out-of-balance"
            />
          )}
        </TableCell>
      </TableRow>
      <TableRow>
        <TableCell sx={{ py: 0 }} colSpan={5}>
          <Collapse in={open} timeout="auto" unmountOnExit>
            <Box sx={{ my: 2 }} aria-label={`ledger-${entry.journalId}`}>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                Double-entry for <b>{entry.reference}</b> — debits on the left,
                credits on the right.
              </Typography>
              <Grid container spacing={2}>
                {/* ---- Debit column ---- */}
                <Grid size={{ xs: 12, md: 6 }}>
                  <TableContainer component={Paper} variant="outlined">
                    <Table size="small" aria-label="debit lines">
                      <TableHead>
                        <TableRow>
                          <TableCell>Debit account</TableCell>
                          <TableCell align="right">Amount</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {debits.map((l, i) => (
                          <TableRow key={`dr-${i}`}>
                            <TableCell>{l.account}</TableCell>
                            <TableCell align="right">
                              {l.amount} {l.currency}
                            </TableCell>
                          </TableRow>
                        ))}
                        {debits.length === 0 ? (
                          <TableRow>
                            <TableCell colSpan={2} sx={{ color: 'text.secondary' }}>
                              No debit lines
                            </TableCell>
                          </TableRow>
                        ) : null}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Grid>
                {/* ---- Credit column ---- */}
                <Grid size={{ xs: 12, md: 6 }}>
                  <TableContainer component={Paper} variant="outlined">
                    <Table size="small" aria-label="credit lines">
                      <TableHead>
                        <TableRow>
                          <TableCell>Credit account</TableCell>
                          <TableCell align="right">Amount</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {credits.map((l, i) => (
                          <TableRow key={`cr-${i}`}>
                            <TableCell>{l.account}</TableCell>
                            <TableCell align="right">
                              {l.amount} {l.currency}
                            </TableCell>
                          </TableRow>
                        ))}
                        {credits.length === 0 ? (
                          <TableRow>
                            <TableCell colSpan={2} sx={{ color: 'text.secondary' }}>
                              No credit lines
                            </TableCell>
                          </TableRow>
                        ) : null}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Grid>
              </Grid>

              {/* ---- Per-currency totals + balance verdict ---- */}
              <Box sx={{ mt: 2 }}>
                <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
                  Totals per currency
                </Typography>
                {byCurrency.map((c) => {
                  const ok = Math.abs(c.debit - c.credit) < 0.005;
                  return (
                    <Typography
                      key={c.currency}
                      variant="body2"
                      sx={{ color: ok ? 'success.main' : 'error.main' }}
                    >
                      {c.currency}: Σ DR {c.debit.toLocaleString()} · Σ CR{' '}
                      {c.credit.toLocaleString()} {ok ? '✓ balanced' : '✗ unbalanced'}
                    </Typography>
                  );
                })}
              </Box>
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </Fragment>
  );
}

export default function JournalPage() {
  const [from, setFrom] = useState(thirtyDaysAgo());
  const [to, setTo] = useState(today());
  const [reference, setReference] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(
    (pageArg, sizeArg) => {
      setLoading(true);
      setError(null);
      const params = {
        from: `${from}T00:00:00Z`,
        to: `${to}T23:59:59Z`,
        reference: reference.trim() || undefined,
        page: pageArg ?? page,
        size: sizeArg ?? size,
      };
      adminApi
        .getJournals(params)
        .then((res) => setData(res))
        .catch((e) =>
          setError(
            e && e.message
              ? e.message
              : 'Couldn’t load journal entries — is the fleet running?',
          ),
        )
        .finally(() => setLoading(false));
    },
    [from, to, reference, page, size],
  );

  useEffect(() => {
    load(0, size);
    // initial mount only — user-driven refresh is via Search / paging.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A new search always resets to the first page.
  const applySearch = () => {
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

  const items = data?.items ?? [];

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Journal
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
        Every money movement posts a balanced double-entry journal. Search by
        transaction reference.
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
              <TextField
                size="small"
                label="Reference / txn ref"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') applySearch();
                }}
                inputProps={{ 'aria-label': 'reference search' }}
              />
            </Grid>
            <Grid size={{ xs: 12, md: 'auto' }}>
              <Button
                variant="contained"
                startIcon={<SearchIcon />}
                onClick={applySearch}
              >
                Search
              </Button>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <ErrorAlert
        message={error}
        onRetry={applySearch}
        title="Couldn’t load journal entries — is the fleet running?"
      />

      {loading && !data ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : items.length === 0 ? (
        !error ? (
          <Paper variant="outlined">
            <EmptyState
              heading="No journal entries in this range"
              description="Try widening the date range or clearing the reference search."
            />
          </Paper>
        ) : null
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="journal entries">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" />
                <TableCell>Time</TableCell>
                <TableCell>Reference</TableCell>
                <TableCell align="right"># lines</TableCell>
                <TableCell>Balanced</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((entry) => (
                <JournalRow key={entry.journalId} entry={entry} />
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
    </Box>
  );
}

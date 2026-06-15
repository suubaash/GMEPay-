'use client';
import * as React from 'react';
import { useRouter } from 'next/navigation';
import {
  Alert,
  Box,
  Chip,
  FormControl,
  Grid,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  TextField,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import { fetchTransactions } from '@/store/transactionsSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';

/**
 * Transactions list — UC-10-02.
 *
 * Wire shape — GET /v1/portal/{partnerId}/transactions returns
 *   Array<TransactionSummary>
 *   {
 *     txnId, partnerId, state, amount, currency, committedAt,
 *     // UC-10-02 additive fields (null until BFF wires them):
 *     qrSchemeId, krwAmount, payerCurrency, payerCurrencyAmount,
 *     appliedFxRate, rateTimestamp, prefundingDeductedUsd
 *   }
 *
 * Columns: timestamp · QR scheme · KRW amount · payer-ccy amount · applied FX rate
 *          · prefunding deducted (USD) · status
 * Filters: date range, status, scheme.
 *
 * Money fields MUST NOT be cast to JS Number — render the decimal string as-is
 * via <MoneyDisplay />.
 */

function todayISO() {
  return new Date().toISOString().slice(0, 10);
}

function thirtyDaysAgoISO() {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return d.toISOString().slice(0, 10);
}

/** Return ISO date string portion (YYYY-MM-DD) from an ISO instant string, or '' */
function toDateStr(isoInstant) {
  if (!isoInstant) return '';
  return String(isoInstant).slice(0, 10);
}

const STATUS_OPTIONS = [
  '',
  'PENDING',
  'APPROVED',
  'COMMITTED',
  'FAILED',
  'CANCELLED',
  'REVERSED',
  'SETTLED'
];

export default function TransactionsPage() {
  const dispatch = useDispatch();
  const router = useRouter();
  const partnerId = currentPartnerId();
  const { items, status, error } = useSelector((s) => s.transactions.list);

  const [page, setPage] = React.useState(0);
  const [size, setSize] = React.useState(25);
  const [sortDir, setSortDir] = React.useState('desc');

  // Filter state
  const [filterFrom, setFilterFrom] = React.useState(thirtyDaysAgoISO());
  const [filterTo, setFilterTo] = React.useState(todayISO());
  const [filterStatus, setFilterStatus] = React.useState('');
  const [filterScheme, setFilterScheme] = React.useState('');

  React.useEffect(() => {
    if (partnerId) dispatch(fetchTransactions({ partnerId, limit: 100 }));
  }, [partnerId, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchTransactions({ partnerId, limit: 100 }));
  }, [partnerId, dispatch]);

  // Derive available scheme options from the loaded items
  const schemeOptions = React.useMemo(() => {
    const list = Array.isArray(items) ? items : [];
    const schemes = new Set();
    list.forEach((t) => { if (t.qrSchemeId) schemes.add(t.qrSchemeId); });
    return ['', ...Array.from(schemes).sort()];
  }, [items]);

  // Hook order: all hooks must run before any conditional return.
  // Client-side filter + sort + page over the array returned by the BFF.
  const filtered = React.useMemo(() => {
    const list = Array.isArray(items) ? [...items] : [];
    return list.filter((t) => {
      const dateStr = toDateStr(t.committedAt);
      if (filterFrom && dateStr && dateStr < filterFrom) return false;
      if (filterTo && dateStr && dateStr > filterTo) return false;
      if (filterStatus && t.state !== filterStatus) return false;
      if (filterScheme && t.qrSchemeId !== filterScheme) return false;
      return true;
    });
  }, [items, filterFrom, filterTo, filterStatus, filterScheme]);

  const sorted = React.useMemo(() => {
    const list = [...filtered];
    list.sort((a, b) => {
      const at = a?.committedAt ?? '';
      const bt = b?.committedAt ?? '';
      const cmp = at < bt ? -1 : at > bt ? 1 : 0;
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return list;
  }, [filtered, sortDir]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  const isInitialLoad = status === 'loading' && (!items || items.length === 0);
  const pageStart = page * size;
  const pageItems = sorted.slice(pageStart, pageStart + size);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Transactions</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only history, newest first. Click a row to view rate-locked details.
        </Typography>
      </Box>

      {/* Filter controls */}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Grid container spacing={2} alignItems="center">
          <Grid item xs={12} sm={6} md={3}>
            <TextField
              label="From date"
              type="date"
              size="small"
              fullWidth
              value={filterFrom}
              onChange={(e) => { setFilterFrom(e.target.value); setPage(0); }}
              InputLabelProps={{ shrink: true }}
              inputProps={{ max: filterTo || todayISO() }}
              data-testid="filter-from"
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <TextField
              label="To date"
              type="date"
              size="small"
              fullWidth
              value={filterTo}
              onChange={(e) => { setFilterTo(e.target.value); setPage(0); }}
              InputLabelProps={{ shrink: true }}
              inputProps={{ min: filterFrom, max: todayISO() }}
              data-testid="filter-to"
            />
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <FormControl size="small" fullWidth>
              <InputLabel>Status</InputLabel>
              <Select
                value={filterStatus}
                label="Status"
                onChange={(e) => { setFilterStatus(e.target.value); setPage(0); }}
                data-testid="filter-status"
              >
                {STATUS_OPTIONS.map((s) => (
                  <MenuItem key={s} value={s}>{s || 'All'}</MenuItem>
                ))}
              </Select>
            </FormControl>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <FormControl size="small" fullWidth>
              <InputLabel>QR Scheme</InputLabel>
              <Select
                value={filterScheme}
                label="QR Scheme"
                onChange={(e) => { setFilterScheme(e.target.value); setPage(0); }}
                data-testid="filter-scheme"
              >
                {schemeOptions.map((s) => (
                  <MenuItem key={s} value={s}>{s || 'All'}</MenuItem>
                ))}
              </Select>
            </FormControl>
          </Grid>
        </Grid>
      </Paper>

      {isInitialLoad && <LoadingSkeleton variant="table" rows={size > 25 ? 10 : Math.max(3, size / 2.5)} />}
      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load transactions.'} onRetry={retry} />
      )}

      {status === 'succeeded' && (
        <Paper variant="outlined">
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Transaction ID</TableCell>
                  <TableCell sortDirection={sortDir}>
                    <TableSortLabel
                      active
                      direction={sortDir}
                      onClick={() => setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))}
                      data-testid="sort-created"
                    >
                      Timestamp
                    </TableSortLabel>
                  </TableCell>
                  <TableCell>QR Scheme</TableCell>
                  <TableCell>KRW Amount</TableCell>
                  <TableCell>Payer Amount</TableCell>
                  <TableCell>FX Rate</TableCell>
                  <TableCell>Prefunding (USD)</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {sorted.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8} sx={{ p: 0, border: 0 }}>
                      <EmptyState
                        title="No transactions yet"
                        message="Once your first payments are processed, they'll show up here."
                      />
                    </TableCell>
                  </TableRow>
                )}
                {pageItems.map((t) => (
                  <TableRow
                    key={t.txnId}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() =>
                      router.push(`/transactions/${encodeURIComponent(t.txnId)}`)
                    }
                  >
                    <TableCell sx={{ fontFamily: 'monospace' }}>{t.txnId}</TableCell>
                    <TableCell>
                      {t.committedAt ? new Date(t.committedAt).toLocaleString() : '—'}
                    </TableCell>
                    <TableCell>
                      {t.qrSchemeId ? (
                        <Chip label={t.qrSchemeId} size="small" variant="outlined" />
                      ) : '—'}
                    </TableCell>
                    <TableCell>
                      {t.krwAmount != null
                        ? <MoneyDisplay amount={t.krwAmount} currency="KRW" />
                        : '—'}
                    </TableCell>
                    <TableCell>
                      {t.payerCurrencyAmount != null && t.payerCurrency
                        ? <MoneyDisplay amount={t.payerCurrencyAmount} currency={t.payerCurrency} />
                        : '—'}
                    </TableCell>
                    <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {t.appliedFxRate != null ? t.appliedFxRate : '—'}
                    </TableCell>
                    <TableCell>
                      {t.prefundingDeductedUsd != null
                        ? <MoneyDisplay amount={t.prefundingDeductedUsd} currency="USD" />
                        : '—'}
                    </TableCell>
                    <TableCell>
                      <StatusChip status={t.state} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          {sorted.length > 0 && (
            <TablePagination
              component="div"
              count={sorted.length}
              page={page}
              onPageChange={(_, p) => setPage(p)}
              rowsPerPage={size}
              onRowsPerPageChange={(e) => {
                setSize(parseInt(e.target.value, 10));
                setPage(0);
              }}
              rowsPerPageOptions={[10, 25, 50, 100]}
            />
          )}
        </Paper>
      )}
    </Stack>
  );
}

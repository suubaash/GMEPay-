'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  Collapse,
  Grid2 as Grid,
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
  TextField,
  Typography,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import FilterListIcon from '@mui/icons-material/FilterList';
import SearchIcon from '@mui/icons-material/Search';
import { useRouter } from 'next/navigation';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchTxnSearch, exportTxnCsv, setFilters } from '@/store/txnSearchSlice';
import ErrorAlert from '@/components/ErrorAlert';
import DateField, { todayISO } from '@/components/DateField';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import EmptyState from '@/components/EmptyState';
import StatusChip from '@/components/StatusChip';

/**
 * Transaction Search page — /transactions/search
 *
 * Advanced search form over GET /v1/admin/transactions (ops BFF pass-through
 * of transaction-mgmt's GET /v1/transactions). Backed by txnSearchSlice.
 *
 * Fields:
 *   txnRef, partnerId, qrSchemeId, direction (INBOUND/OUTBOUND/DOMESTIC),
 *   status, date-range (from/to), amount-range (amountMin/amountMax).
 *
 * Results table (paginated):
 *   txnRef, partner, scheme, KRW amount, payer-ccy amount,
 *   applied FX rate, status, committed-at (KST).
 *   Row click -> /transactions/[txnRef] detail.
 *
 * CSV export triggers exportTxnCsv with current filter params.
 *
 * Money values: BigDecimal-as-string from wire — rendered as-is, never cast.
 * Timestamps: shown as KST (Asia/Seoul). The BFF emits ISO-8601 with +09:00
 * offset so the browser renders them in local time automatically; we force
 * KST by formatting with Intl.DateTimeFormat.
 */

// ---------- helpers ----------

const DIRECTIONS = ['', 'INBOUND', 'OUTBOUND', 'DOMESTIC'];
const STATUSES = ['', 'CREATED', 'QUOTED', 'APPROVED', 'SETTLED', 'FAILED', 'CANCELLED'];

function toKst(isoString) {
  if (!isoString) return '—';
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(new Date(isoString));
  } catch {
    return isoString;
  }
}

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

const EMPTY_FORM = {
  txnRef: '',
  partnerId: '',
  qrSchemeId: '',
  direction: '',
  status: '',
  from: '',
  to: '',
  amountMin: '',
  amountMax: '',
};

// ---------- sub-components ----------

function SearchForm({ onSearch, loading }) {
  const [form, setForm] = useState(EMPTY_FORM);
  const [open, setOpen] = useState(true);

  const set = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const handleSubmit = (e) => {
    e.preventDefault();
    // Strip empty strings before dispatch — qs() skips them but let's be clean.
    const params = {};
    for (const [k, v] of Object.entries(form)) {
      if (v !== '') params[k] = v;
    }
    onSearch(params);
  };

  const handleReset = () => {
    setForm(EMPTY_FORM);
    onSearch({});
  };

  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
      <Box
        sx={{ display: 'flex', alignItems: 'center', cursor: 'pointer', mb: open ? 2 : 0 }}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setOpen((o) => !o); }}
        aria-label="Toggle search filters"
      >
        <FilterListIcon sx={{ mr: 1 }} />
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Search filters
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {open ? 'Collapse' : 'Expand'}
        </Typography>
      </Box>

      <Collapse in={open}>
        <Box component="form" onSubmit={handleSubmit} aria-label="Transaction search form">
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                label="Transaction ref"
                value={form.txnRef}
                onChange={set('txnRef')}
                inputProps={{ 'aria-label': 'Transaction ref' }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                label="Partner ID"
                value={form.partnerId}
                onChange={set('partnerId')}
                inputProps={{ 'aria-label': 'Partner ID' }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField
                fullWidth
                size="small"
                label="QR scheme"
                value={form.qrSchemeId}
                onChange={set('qrSchemeId')}
                inputProps={{ 'aria-label': 'QR scheme' }}
              />
            </Grid>

            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Direction"
                value={form.direction}
                onChange={set('direction')}
                inputProps={{ 'aria-label': 'Direction' }}
                SelectProps={{ displayEmpty: true }}
              >
                {DIRECTIONS.map((d) => (
                  <MenuItem key={d} value={d}>
                    {d || 'Any'}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Status"
                value={form.status}
                onChange={set('status')}
                inputProps={{ 'aria-label': 'Status' }}
                SelectProps={{ displayEmpty: true }}
              >
                {STATUSES.map((s) => (
                  <MenuItem key={s} value={s}>
                    {s || 'Any'}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <DateField
                fullWidth
                size="small"
                label="Date from"
                value={form.from}
                onChange={set('from')}
                max={form.to || todayISO()}
                inputProps={{ 'aria-label': 'Date from' }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <DateField
                fullWidth
                size="small"
                label="Date to"
                value={form.to}
                onChange={set('to')}
                min={form.from || undefined}
                max={todayISO()}
                inputProps={{ 'aria-label': 'Date to' }}
              />
            </Grid>

            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                fullWidth
                size="small"
                label="Amount min"
                value={form.amountMin}
                onChange={set('amountMin')}
                inputProps={{ inputMode: 'decimal', pattern: '^\\d+(\\.\\d{1,2})?$', min: 0, 'aria-label': 'Amount min' }}
                helperText="KRW string"
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                fullWidth
                size="small"
                label="Amount max"
                value={form.amountMax}
                onChange={set('amountMax')}
                inputProps={{ inputMode: 'decimal', pattern: '^\\d+(\\.\\d{1,2})?$', min: 0, 'aria-label': 'Amount max' }}
                helperText="KRW string"
              />
            </Grid>

            <Grid size={12}>
              <Stack direction="row" spacing={1}>
                <Button
                  type="submit"
                  variant="contained"
                  startIcon={<SearchIcon />}
                  disabled={loading}
                  aria-label="Search transactions"
                >
                  Search
                </Button>
                <Button
                  type="button"
                  variant="outlined"
                  onClick={handleReset}
                  disabled={loading}
                  aria-label="Reset filters"
                >
                  Reset
                </Button>
              </Stack>
            </Grid>
          </Grid>
        </Box>
      </Collapse>
    </Paper>
  );
}

// ---------- main page ----------

export default function TxnSearchPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { items, page, size, totalElements, loading, error, filters, csvLoading, csvError } =
    useAppSelector((s) => s.txnSearch);

  // Trigger initial load (empty params = last 30 days or backend default).
  useEffect(() => {
    dispatch(fetchTxnSearch({ page: 0, size: 20 }));
  }, [dispatch]);

  const handleSearch = useCallback(
    (params) => {
      dispatch(fetchTxnSearch({ ...params, page: 0, size: 20 }));
    },
    [dispatch],
  );

  const handlePageChange = useCallback(
    (_e, newPage) => {
      dispatch(fetchTxnSearch({ ...filters, page: newPage, size }));
    },
    [dispatch, filters, size],
  );

  const handleRowsPerPageChange = useCallback(
    (e) => {
      const newSize = parseInt(e.target.value, 10);
      dispatch(fetchTxnSearch({ ...filters, page: 0, size: newSize }));
    },
    [dispatch, filters],
  );

  const handleExportCsv = useCallback(async () => {
    // Export with current filters but no pagination.
    const { page: _p, size: _s, ...exportFilters } = filters;
    const result = await dispatch(exportTxnCsv(exportFilters));
    if (exportTxnCsv.fulfilled.match(result)) {
      const ts = new Date().toISOString().slice(0, 10);
      triggerCsvDownload(result.payload, `transactions-${ts}.csv`);
    }
  }, [dispatch, filters]);

  const handleRowClick = useCallback(
    (txnRef) => {
      router.push(`/transactions/${encodeURIComponent(txnRef)}`);
    },
    [router],
  );

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Transaction Search
        </Typography>
        <Button
          variant="outlined"
          startIcon={<DownloadIcon />}
          onClick={handleExportCsv}
          disabled={csvLoading || loading || items.length === 0}
          aria-label="Export results as CSV"
        >
          {csvLoading ? 'Exporting…' : 'Export CSV'}
        </Button>
      </Box>

      <SearchForm onSearch={handleSearch} loading={loading} />

      <ErrorAlert
        message={error}
        onRetry={() => dispatch(fetchTxnSearch(filters))}
        title="Could not load transactions"
      />
      <ErrorAlert
        message={csvError}
        title="CSV export failed"
        severity="warning"
      />

      {loading && items.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && items.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No transactions found"
            description="Adjust the filters above and press Search to find transactions."
          />
        </Paper>
      ) : (
        <Paper>
          <TableContainer>
            <Table aria-label="Transaction search results">
              <TableHead>
                <TableRow>
                  <TableCell>Txn ref</TableCell>
                  <TableCell>Partner</TableCell>
                  <TableCell>Scheme</TableCell>
                  <TableCell align="right">KRW amount</TableCell>
                  <TableCell>Payer CCY</TableCell>
                  <TableCell align="right">Payer amount</TableCell>
                  <TableCell align="right">FX rate</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Committed (KST)</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {items.map((row) => (
                  <TableRow
                    key={row.txnRef}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => handleRowClick(row.txnRef)}
                    aria-label={`Transaction ${row.txnRef}`}
                  >
                    <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>
                      {row.txnRef ?? '—'}
                    </TableCell>
                    <TableCell>{row.partnerRef ?? '—'}</TableCell>
                    <TableCell>
                      {row.qrSchemeId ? (
                        <Chip label={row.qrSchemeId} size="small" variant="outlined" />
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    {/* Money: render as-is — BigDecimal string, never Number() cast */}
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {row.krwAmount ?? '—'}
                    </TableCell>
                    <TableCell>{row.payerCurrency ?? '—'}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {row.payerCurrencyAmount ?? '—'}
                    </TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                      {row.appliedFxRate ?? '—'}
                    </TableCell>
                    <TableCell>
                      <StatusChip status={row.status} />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {toKst(row.createdAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={totalElements}
            page={page}
            rowsPerPage={size}
            onPageChange={handlePageChange}
            onRowsPerPageChange={handleRowsPerPageChange}
            rowsPerPageOptions={[10, 20, 50, 100]}
            aria-label="Transaction search pagination"
          />
        </Paper>
      )}
    </Box>
  );
}

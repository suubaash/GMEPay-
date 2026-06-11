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
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import { useRouter } from 'next/navigation';
import { useAppDispatch, useAppSelector } from '@/store';
import { searchTransactions } from '@/store/transactionsSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { TXN_STATES } from '@/api/constants';

/**
 * Transactions search page.
 *
 * GET /v1/admin/transactions?partnerId&schemeId&status&fromDate&toDate&page&size
 * -> Page<TransactionSummary> = { content, page, size, total }
 *
 * TransactionSummary fields used by the table:
 *   { txnId, partnerId, state, amount (decimal string), currency, committedAt }
 *
 * Note: the BFF accepts `status` as the query-param name but the response
 * field is `state` (carry-over from the transaction-mgmt state machine).
 */
const EMPTY_FORM = {
  partnerId: '',
  schemeId: '',
  status: '',
  fromDate: '',
  toDate: '',
};

export default function TransactionsPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { items, loading, error, page, size, total } = useAppSelector(
    (s) => s.transactions,
  );

  const [form, setForm] = useState(EMPTY_FORM);
  const [activeFilters, setActiveFilters] = useState({
    page: 0,
    size: 20,
  });

  const runSearch = useCallback(
    (filters) => {
      setActiveFilters(filters);
      dispatch(searchTransactions(filters));
    },
    [dispatch],
  );

  useEffect(() => {
    runSearch({ page: 0, size: 20 });
  }, [runSearch]);

  const onApply = () => {
    const filters = {
      partnerId: form.partnerId || undefined,
      schemeId: form.schemeId || undefined,
      status: form.status || undefined,
      fromDate: form.fromDate || undefined,
      toDate: form.toDate || undefined,
      page: 0,
      size: activeFilters.size ?? 20,
    };
    runSearch(filters);
  };

  const onClear = () => {
    setForm(EMPTY_FORM);
    runSearch({ page: 0, size: activeFilters.size ?? 20 });
  };

  const onPageChange = (_e, newPage) => {
    runSearch({ ...activeFilters, page: newPage });
  };

  const onSizeChange = (e) => {
    runSearch({ ...activeFilters, page: 0, size: Number.parseInt(e.target.value, 10) });
  };

  const rows = Array.isArray(items) ? items : [];
  const isEmpty = !loading && rows.length === 0;

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Transactions
      </Typography>

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                label="Partner ID"
                size="small"
                fullWidth
                value={form.partnerId}
                onChange={(e) => setForm((f) => ({ ...f, partnerId: e.target.value }))}
                inputProps={{ 'aria-label': 'Partner ID' }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                label="Scheme ID"
                size="small"
                fullWidth
                value={form.schemeId}
                onChange={(e) => setForm((f) => ({ ...f, schemeId: e.target.value }))}
                inputProps={{ 'aria-label': 'Scheme ID' }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 2 }}>
              <FormControl size="small" fullWidth>
                <InputLabel id="status-filter-label">Status</InputLabel>
                <Select
                  labelId="status-filter-label"
                  label="Status"
                  value={form.status}
                  onChange={(e) =>
                    setForm((f) => ({ ...f, status: e.target.value }))
                  }
                >
                  <MenuItem value="">Any</MenuItem>
                  {TXN_STATES.map((s) => (
                    <MenuItem key={s} value={s}>
                      {s}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>
            <Grid size={{ xs: 6, sm: 3, md: 2 }}>
              <TextField
                label="From"
                type="date"
                size="small"
                fullWidth
                InputLabelProps={{ shrink: true }}
                value={form.fromDate}
                onChange={(e) => setForm((f) => ({ ...f, fromDate: e.target.value }))}
                inputProps={{ 'aria-label': 'From date' }}
              />
            </Grid>
            <Grid size={{ xs: 6, sm: 3, md: 2 }}>
              <TextField
                label="To"
                type="date"
                size="small"
                fullWidth
                InputLabelProps={{ shrink: true }}
                value={form.toDate}
                onChange={(e) => setForm((f) => ({ ...f, toDate: e.target.value }))}
                inputProps={{ 'aria-label': 'To date' }}
              />
            </Grid>
            <Grid size={12}>
              <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
                <Button onClick={onClear}>Clear</Button>
                <Button variant="contained" startIcon={<SearchIcon />} onClick={onApply}>
                  Search
                </Button>
              </Box>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <ErrorAlert
        message={error}
        onRetry={() => runSearch(activeFilters)}
        title="Could not load transactions"
      />

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={8} />
      ) : isEmpty && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No transactions match those filters"
            description="Adjust the filters above or clear them to see all transactions."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Transaction ID</TableCell>
                <TableCell>Partner</TableCell>
                <TableCell>Amount</TableCell>
                <TableCell>Currency</TableCell>
                <TableCell>State</TableCell>
                <TableCell>Committed</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((t) => (
                <TableRow
                  key={t.txnId}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() =>
                    router.push(`/transactions/${encodeURIComponent(t.txnId)}`)
                  }
                >
                  <TableCell>{t.txnId ?? '—'}</TableCell>
                  <TableCell>{t.partnerId ?? '—'}</TableCell>
                  <TableCell>
                    <MoneyDisplay
                      amount={t.amount}
                      currency={t.currency ?? ''}
                      withCurrency={false}
                    />
                  </TableCell>
                  <TableCell>{t.currency ?? '—'}</TableCell>
                  <TableCell>
                    <StatusChip state={t.state} />
                  </TableCell>
                  <TableCell>{t.committedAt ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <TablePagination
            component="div"
            count={total ?? 0}
            page={page ?? 0}
            onPageChange={onPageChange}
            rowsPerPage={size ?? 20}
            onRowsPerPageChange={onSizeChange}
            rowsPerPageOptions={[10, 20, 50, 100]}
          />
        </TableContainer>
      )}
    </Box>
  );
}

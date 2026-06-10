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
import { TXN_STATUSES, type TransactionSearchFilters, type TxnStatus } from '@/api/types';

interface FilterForm {
  partnerId: string;
  schemeId: string;
  status: TxnStatus | '';
  fromDate: string;
  toDate: string;
}

const EMPTY_FORM: FilterForm = {
  partnerId: '',
  schemeId: '',
  status: '',
  fromDate: '',
  toDate: '',
};

/**
 * Transactions search page.
 *
 * Top section: filter form (partnerId, schemeId, status dropdown, from/to dates).
 * Below: paginated MUI Table — page/size controls via MUI TablePagination.
 *
 * Row click navigates to /transactions/{id} for the full detail view, which
 * surfaces the per-partner rounding-lock fields (booked, mode, residual).
 */
export default function TransactionsPage() {
  const dispatch = useAppDispatch();
  const router = useRouter();
  const { items, loading, error, page, size, totalElements } = useAppSelector(
    (s) => s.transactions,
  );

  const [form, setForm] = useState<FilterForm>(EMPTY_FORM);
  const [activeFilters, setActiveFilters] = useState<TransactionSearchFilters>({
    page: 0,
    size: 20,
  });

  const runSearch = useCallback(
    (filters: TransactionSearchFilters) => {
      setActiveFilters(filters);
      dispatch(searchTransactions(filters));
    },
    [dispatch],
  );

  // initial load -> empty filters, first page
  useEffect(() => {
    runSearch({ page: 0, size: 20 });
  }, [runSearch]);

  const onApply = () => {
    const filters: TransactionSearchFilters = {
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

  const onPageChange = (_e: unknown, newPage: number) => {
    runSearch({ ...activeFilters, page: newPage });
  };

  const onSizeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    runSearch({ ...activeFilters, page: 0, size: Number.parseInt(e.target.value, 10) });
  };

  const isEmpty = !loading && items.length === 0;

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
                    setForm((f) => ({ ...f, status: e.target.value as TxnStatus | '' }))
                  }
                >
                  <MenuItem value="">Any</MenuItem>
                  {TXN_STATUSES.map((s) => (
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

      <ErrorAlert message={error} onRetry={() => runSearch(activeFilters)} title="Could not load transactions" />

      {loading && items.length === 0 ? (
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
                <TableCell>Scheme</TableCell>
                <TableCell>Amount</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Created</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {items.map((t) => (
                <TableRow
                  key={t.id}
                  hover
                  sx={{ cursor: 'pointer' }}
                  onClick={() => router.push(`/transactions/${encodeURIComponent(t.id)}`)}
                >
                  <TableCell>{t.id}</TableCell>
                  <TableCell>{t.partnerId}</TableCell>
                  <TableCell>{t.scheme}</TableCell>
                  <TableCell>
                    <MoneyDisplay value={t.amount} />
                  </TableCell>
                  <TableCell>
                    <StatusChip status={t.status} />
                  </TableCell>
                  <TableCell>{t.createdAt}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <TablePagination
            component="div"
            count={totalElements}
            page={page}
            onPageChange={onPageChange}
            rowsPerPage={size}
            onRowsPerPageChange={onSizeChange}
            rowsPerPageOptions={[10, 20, 50, 100]}
          />
        </TableContainer>
      )}
    </Box>
  );
}

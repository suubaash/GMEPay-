'use client';
import * as React from 'react';
import { useRouter } from 'next/navigation';
import {
  Alert,
  Box,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
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
 * Transactions list.
 *
 * Wire shape — GET /v1/portal/{partnerId}/transactions returns
 *   Array<TransactionSummary>
 *     { txnId, partnerId, state, amount, currency, committedAt }
 *
 * Phase-1 portal endpoint returns a plain list (NOT the Admin Page<T>
 * envelope). We page client-side for the UI.
 */
export default function TransactionsPage() {
  const dispatch = useDispatch();
  const router = useRouter();
  const partnerId = currentPartnerId();
  const { items, status, error } = useSelector((s) => s.transactions.list);

  const [page, setPage] = React.useState(0);
  const [size, setSize] = React.useState(25);
  const [sortDir, setSortDir] = React.useState('desc');

  React.useEffect(() => {
    if (partnerId) dispatch(fetchTransactions({ partnerId, limit: 100 }));
  }, [partnerId, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchTransactions({ partnerId, limit: 100 }));
  }, [partnerId, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  const isInitialLoad = status === 'loading' && (!items || items.length === 0);

  // Client-side sort + page over the array returned by the BFF.
  const sorted = React.useMemo(() => {
    const list = Array.isArray(items) ? [...items] : [];
    list.sort((a, b) => {
      const at = a?.committedAt ?? '';
      const bt = b?.committedAt ?? '';
      const cmp = at < bt ? -1 : at > bt ? 1 : 0;
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return list;
  }, [items, sortDir]);

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
                      Committed
                    </TableSortLabel>
                  </TableCell>
                  <TableCell>Amount</TableCell>
                  <TableCell>State</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {sorted.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} sx={{ p: 0, border: 0 }}>
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
                      <MoneyDisplay amount={t.amount ?? '0'} currency={t.currency ?? ''} />
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

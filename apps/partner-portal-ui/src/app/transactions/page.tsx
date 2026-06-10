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
import type { AppDispatch, RootState } from '@/store';
import { fetchTransactionsPage } from '@/store/transactionsSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';

type SortDir = 'asc' | 'desc';

export default function TransactionsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const router = useRouter();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s: RootState) => s.transactions.page);

  const [page, setPage] = React.useState(0);
  const [size, setSize] = React.useState(25);
  // Sort by date desc by default per spec.
  const [sortDir, setSortDir] = React.useState<SortDir>('desc');

  const sort = `createdAt,${sortDir}`;

  React.useEffect(() => {
    if (partnerId) dispatch(fetchTransactionsPage({ partnerId, page, size, sort }));
  }, [partnerId, page, size, sort, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchTransactionsPage({ partnerId, page, size, sort }));
  }, [partnerId, page, size, sort, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  const isInitialLoad = status === 'loading' && !data;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Transactions</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only history, newest first. Click a row to view rate-locked details.
        </Typography>
      </Box>

      {isInitialLoad && <LoadingSkeleton variant="table" rows={size > 25 ? 10 : size / 2.5} />}
      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load transactions.'} onRetry={retry} />
      )}

      {data && (
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
                      Created
                    </TableSortLabel>
                  </TableCell>
                  <TableCell>Send</TableCell>
                  <TableCell>Payout</TableCell>
                  <TableCell>Scheme</TableCell>
                  <TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.items.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={6} sx={{ p: 0, border: 0 }}>
                      <EmptyState
                        title="No transactions yet"
                        message="Once your first payments are processed, they'll show up here."
                      />
                    </TableCell>
                  </TableRow>
                )}
                {data.items.map((t) => (
                  <TableRow
                    key={t.txnId}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() =>
                      router.push(`/transactions/${encodeURIComponent(t.txnId)}`)
                    }
                  >
                    <TableCell sx={{ fontFamily: 'monospace' }}>{t.txnId}</TableCell>
                    <TableCell>{new Date(t.createdAt).toLocaleString()}</TableCell>
                    <TableCell>
                      <MoneyDisplay money={t.sendAmount} />
                    </TableCell>
                    <TableCell>
                      <MoneyDisplay money={t.payoutAmount} />
                    </TableCell>
                    <TableCell>{t.scheme ?? '—'}</TableCell>
                    <TableCell>
                      <StatusChip status={t.status} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={data.total}
            page={page}
            onPageChange={(_, p) => setPage(p)}
            rowsPerPage={size}
            onRowsPerPageChange={(e) => {
              setSize(parseInt(e.target.value, 10));
              setPage(0);
            }}
            rowsPerPageOptions={[10, 25, 50, 100]}
          />
        </Paper>
      )}
    </Stack>
  );
}

'use client';
import * as React from 'react';
import Link from 'next/link';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import { fetchTransactions } from '@/store/portalSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';

export default function TransactionsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s: RootState) => s.portal.transactions);

  const [page, setPage] = React.useState(0);
  const [size, setSize] = React.useState(25);

  React.useEffect(() => {
    if (partnerId) dispatch(fetchTransactions({ partnerId, page, size }));
  }, [partnerId, page, size, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id configured. Set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Transactions</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only history. Click a transaction to view its rate-locked details.
        </Typography>
      </Box>

      {status === 'loading' && !data && <CircularProgress />}
      {status === 'failed' && <Alert severity="error">{error}</Alert>}

      {data && (
        <Paper variant="outlined">
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Transaction ID</TableCell>
                  <TableCell>Created</TableCell>
                  <TableCell>Send</TableCell>
                  <TableCell>Payout</TableCell>
                  <TableCell>Scheme</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {data.items.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={7} sx={{ textAlign: 'center', py: 4 }}>
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        No transactions yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {data.items.map((t) => (
                  <TableRow key={t.txnId} hover>
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
                    <TableCell align="right">
                      <Button
                        size="small"
                        component={Link}
                        href={`/transactions/${encodeURIComponent(t.txnId)}`}
                      >
                        View
                      </Button>
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

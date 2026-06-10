'use client';
import * as React from 'react';
import Link from 'next/link';
import {
  Box,
  Card,
  CardContent,
  Grid,
  Typography,
  Stack,
  Button,
  CircularProgress,
  Alert
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import { fetchBalance, fetchTransactions } from '@/store/portalSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';

export default function OverviewPage() {
  const dispatch = useDispatch<AppDispatch>();
  const partnerId = currentPartnerId();
  const balance = useSelector((s: RootState) => s.portal.balance);
  const transactions = useSelector((s: RootState) => s.portal.transactions);

  React.useEffect(() => {
    if (!partnerId) return;
    if (balance.status === 'idle') dispatch(fetchBalance(partnerId));
    if (transactions.status === 'idle') {
      dispatch(fetchTransactions({ partnerId, page: 0, size: 10 }));
    }
  }, [partnerId, balance.status, transactions.status, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id configured. Set <code>NEXT_PUBLIC_PARTNER_ID</code> in your local env.
      </Alert>
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Overview</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          A read-only snapshot of your GMEPay+ account.
        </Typography>
      </Box>

      <Grid container spacing={3}>
        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Current balance
              </Typography>
              {balance.status === 'loading' && <CircularProgress size={20} sx={{ mt: 1 }} />}
              {balance.status === 'failed' && (
                <Alert severity="error" sx={{ mt: 1 }}>
                  {balance.error}
                </Alert>
              )}
              {balance.data && (
                <Typography variant="h2" sx={{ mt: 1 }}>
                  <MoneyDisplay money={balance.data.balance} />
                </Typography>
              )}
              <Button component={Link} href="/balance" sx={{ mt: 2 }} size="small">
                View details
              </Button>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Transactions (recent)
              </Typography>
              {transactions.status === 'loading' && (
                <CircularProgress size={20} sx={{ mt: 1 }} />
              )}
              {transactions.data && (
                <Typography variant="h2" sx={{ mt: 1 }}>
                  {transactions.data.total.toLocaleString()}
                </Typography>
              )}
              <Button component={Link} href="/transactions" sx={{ mt: 2 }} size="small">
                View history
              </Button>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Last settlement
              </Typography>
              <Typography variant="h4" sx={{ mt: 1 }}>
                {balance.data?.lastSettlementAt
                  ? new Date(balance.data.lastSettlementAt).toLocaleString()
                  : '—'}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Stack>
  );
}

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
  Alert
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import { fetchOverview } from '@/store/overviewSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';

export default function OverviewPage() {
  const dispatch = useDispatch<AppDispatch>();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s: RootState) => s.overview);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchOverview(partnerId));
  }, [partnerId, status, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchOverview(partnerId));
  }, [partnerId, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
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

      {status === 'failed' && (
        <ErrorAlert
          message={error ?? 'Failed to load overview.'}
          onRetry={retry}
        />
      )}

      <Grid container spacing={3}>
        <Grid item xs={12} md={3}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Current balance
              </Typography>
              {status === 'loading' && (
                <Box sx={{ mt: 1 }}>
                  <LoadingSkeleton variant="stat" />
                </Box>
              )}
              {data && (
                <Typography variant="h2" sx={{ mt: 1 }}>
                  <MoneyDisplay money={data.balance} showRawTooltip />
                </Typography>
              )}
              <Button component={Link} href="/balance" sx={{ mt: 2 }} size="small">
                View details
              </Button>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={3}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Low-balance threshold
              </Typography>
              {status === 'loading' && (
                <Box sx={{ mt: 1 }}>
                  <LoadingSkeleton variant="stat" />
                </Box>
              )}
              {data && (
                <Typography variant="h2" sx={{ mt: 1 }}>
                  <MoneyDisplay money={data.lowBalanceThreshold} />
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={3}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Recent activity (rolling)
              </Typography>
              {status === 'loading' && (
                <Box sx={{ mt: 1 }}>
                  <LoadingSkeleton variant="stat" />
                </Box>
              )}
              {data && (
                <Typography variant="h2" sx={{ mt: 1 }}>
                  {data.recentActivityCount.toLocaleString()}
                </Typography>
              )}
              <Button component={Link} href="/transactions" sx={{ mt: 2 }} size="small">
                View history
              </Button>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={3}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Last settlement
              </Typography>
              {status === 'loading' && (
                <Box sx={{ mt: 1 }}>
                  <LoadingSkeleton variant="stat" />
                </Box>
              )}
              {data && (
                <Typography variant="h4" sx={{ mt: 1 }}>
                  {data.lastSettlementAt
                    ? new Date(data.lastSettlementAt).toLocaleString()
                    : '—'}
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {data && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          Balance last updated{' '}
          {new Date(data.balanceLastUpdatedAt).toLocaleString()}.
        </Typography>
      )}
    </Stack>
  );
}

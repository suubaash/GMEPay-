'use client';
import * as React from 'react';
import Link from 'next/link';
import dynamic from 'next/dynamic';
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
import { fetchOverview } from '@/store/overviewSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import welcomeAnimation from '@/lottie/welcome.json';

// Lottie is browser-only — import lazily so it doesn't break SSR.
const Lottie = dynamic(() => import('lottie-react'), { ssr: false });

/**
 * Overview page — landing screen for a signed-in partner.
 *
 * Wire shape (PartnerOverview):
 *   { partnerId, balance: { partnerId, currency, balance, lowBalanceThreshold },
 *     recentTxnCount, lastSettlementDate }
 *
 * `balance`, `lowBalanceThreshold` and `currency` live INSIDE the nested
 * BalanceView. There is no top-level `displayName`, `recentActivityCount`,
 * `lastSettlementAt`, or `balanceLastUpdatedAt` — those names are stale and
 * crash the page.
 */
export default function OverviewPage() {
  const dispatch = useDispatch();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s) => s.overview);

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

  const balance = data?.balance;
  const currency = balance?.currency ?? '';
  const balanceAmount = balance?.balance ?? '0';
  const thresholdAmount = balance?.lowBalanceThreshold ?? '0';
  const recentCount = data?.recentTxnCount ?? 0;
  const lastSettlementDate = data?.lastSettlementDate ?? null;

  return (
    <Stack spacing={3}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
        <Box sx={{ width: 64, height: 64, flexShrink: 0 }} aria-hidden data-testid="overview-welcome-lottie">
          <Lottie animationData={welcomeAnimation} loop autoplay />
        </Box>
        <Box>
          <Typography variant="h1">Overview</Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            A read-only snapshot of your GMEPay+ account.
          </Typography>
        </Box>
      </Box>

      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load overview.'} onRetry={retry} />
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
                  <MoneyDisplay amount={balanceAmount} currency={currency} showRawTooltip />
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
                  <MoneyDisplay amount={thresholdAmount} currency={currency} />
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={3}>
          <Card>
            <CardContent>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                Recent activity
              </Typography>
              {status === 'loading' && (
                <Box sx={{ mt: 1 }}>
                  <LoadingSkeleton variant="stat" />
                </Box>
              )}
              {data && (
                <Typography variant="h2" sx={{ mt: 1 }}>
                  {Number(recentCount).toLocaleString()}
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
                  {lastSettlementDate
                    ? new Date(lastSettlementDate).toLocaleDateString()
                    : '—'}
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Stack>
  );
}

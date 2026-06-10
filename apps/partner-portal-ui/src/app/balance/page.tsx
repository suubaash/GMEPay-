'use client';
import * as React from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  Grid,
  Stack,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import dynamic from 'next/dynamic';
import type { AppDispatch, RootState } from '@/store';
import { fetchBalance } from '@/store/portalSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';

// Lottie is browser-only — import lazily.
const Lottie = dynamic(() => import('lottie-react'), { ssr: false });

/**
 * A tiny inline Lottie animation (pulse green) so this page is self-contained
 * without shipping a separate JSON asset. Real animations would live under
 * /public/lottie/*.json.
 */
const HEALTHY_PULSE_LOTTIE = {
  v: '5.7.1',
  fr: 30,
  ip: 0,
  op: 60,
  w: 200,
  h: 200,
  nm: 'pulse',
  ddd: 0,
  assets: [],
  layers: [
    {
      ddd: 0,
      ind: 1,
      ty: 4,
      nm: 'circle',
      sr: 1,
      ks: {
        o: { a: 1, k: [{ t: 0, s: [80] }, { t: 30, s: [40] }, { t: 60, s: [80] }] },
        r: { a: 0, k: 0 },
        p: { a: 0, k: [100, 100, 0] },
        a: { a: 0, k: [0, 0, 0] },
        s: { a: 1, k: [{ t: 0, s: [80, 80, 100] }, { t: 30, s: [110, 110, 100] }, { t: 60, s: [80, 80, 100] }] }
      },
      ao: 0,
      shapes: [
        {
          ty: 'el',
          d: 1,
          s: { a: 0, k: [120, 120] },
          p: { a: 0, k: [0, 0] }
        },
        {
          ty: 'fl',
          c: { a: 0, k: [0.086, 0.639, 0.290, 1] },
          o: { a: 0, k: 100 }
        }
      ],
      ip: 0,
      op: 60,
      st: 0,
      bm: 0
    }
  ],
  markers: []
};

function isHealthy(balanceAmount: string, thresholdAmount: string): boolean {
  const b = Number(balanceAmount);
  const t = Number(thresholdAmount);
  if (!Number.isFinite(b) || !Number.isFinite(t)) return false;
  return b > t;
}

export default function BalancePage() {
  const dispatch = useDispatch<AppDispatch>();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s: RootState) => s.portal.balance);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchBalance(partnerId));
  }, [partnerId, status, dispatch]);

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
        <Typography variant="h1">Balance</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Your current prefunding balance and low-balance threshold.
        </Typography>
      </Box>

      {status === 'loading' && <CircularProgress />}
      {status === 'failed' && <Alert severity="error">{error}</Alert>}

      {data && (
        <Card>
          <CardContent>
            <Grid container spacing={3} alignItems="center">
              <Grid item xs={12} md={8}>
                <Stack spacing={2}>
                  <Box>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      Current balance
                    </Typography>
                    <Typography variant="h1" sx={{ mt: 0.5 }}>
                      <MoneyDisplay money={data.balance} parenthesizeNegative />
                    </Typography>
                  </Box>

                  <Divider flexItem />

                  <Stack direction="row" spacing={3} flexWrap="wrap">
                    <Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Low-balance threshold
                      </Typography>
                      <Typography variant="h4">
                        <MoneyDisplay money={data.lowBalanceThreshold} />
                      </Typography>
                    </Box>
                    <Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Status
                      </Typography>
                      <Box sx={{ mt: 0.5 }}>
                        {isHealthy(data.balance.amount, data.lowBalanceThreshold.amount) ? (
                          <Chip label="Healthy" color="success" />
                        ) : (
                          <Chip label="Below threshold" color="warning" />
                        )}
                      </Box>
                    </Box>
                    <Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Last updated
                      </Typography>
                      <Typography variant="body1">
                        {new Date(data.lastUpdatedAt).toLocaleString()}
                      </Typography>
                    </Box>
                  </Stack>
                </Stack>
              </Grid>

              <Grid item xs={12} md={4} sx={{ textAlign: 'center' }}>
                {isHealthy(data.balance.amount, data.lowBalanceThreshold.amount) ? (
                  <Box sx={{ width: 200, height: 200, mx: 'auto' }}>
                    <Lottie animationData={HEALTHY_PULSE_LOTTIE} loop autoplay />
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      Balance healthy
                    </Typography>
                  </Box>
                ) : (
                  <Alert severity="warning">
                    Balance is at or below your configured low-balance threshold. Top up to avoid
                    payment failures.
                  </Alert>
                )}
              </Grid>
            </Grid>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

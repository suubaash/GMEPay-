'use client';

import { useEffect } from 'react';
import { Box, Card, CardContent, Grid2 as Grid, Typography, Alert, CircularProgress } from '@mui/material';
import Lottie from 'lottie-react';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchDashboard } from '@/store/dashboardSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import emptyLottie from '@/lottie/empty.json';

/**
 * Dashboard home page.
 *
 * Renders 4 MUI Card components fed by GET /v1/admin/dashboard (via the BFF).
 * While the dashboard is empty (no data yet, e.g. fresh install), a placeholder
 * Lottie animation is shown — see src/lottie/empty.json for the placeholder
 * note; replace with a real file before production.
 */
export default function DashboardPage() {
  const dispatch = useAppDispatch();
  const { data, loading, error } = useAppSelector((s) => s.dashboard);

  useEffect(() => {
    dispatch(fetchDashboard());
  }, [dispatch]);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Dashboard
      </Typography>

      {error ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          Could not load dashboard metrics: {error}
        </Alert>
      ) : null}

      {loading && !data ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : null}

      {!data && !loading ? (
        <Card variant="outlined" sx={{ textAlign: 'center', p: 4 }}>
          <Box sx={{ width: 200, height: 200, mx: 'auto' }}>
            <Lottie animationData={emptyLottie} loop autoplay />
          </Box>
          <Typography variant="h4">No data yet</Typography>
          <Typography color="text.secondary">
            Once partners start transacting, KPIs will appear here.
          </Typography>
        </Card>
      ) : null}

      {data ? (
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Transactions today
                </Typography>
                <Typography variant="h3">{data.txnCountToday.toLocaleString()}</Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Approved volume today
                </Typography>
                <Typography variant="h3" component="div">
                  <MoneyDisplay value={data.approvedVolumeToday} />
                </Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Active partners
                </Typography>
                <Typography variant="h3">{data.activePartners}</Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Rolling failure rate
                </Typography>
                <Typography variant="h3">
                  {(data.rollingFailureRate * 100).toFixed(2)}%
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      ) : null}
    </Box>
  );
}

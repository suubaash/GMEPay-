'use client';

import { useCallback, useEffect } from 'react';
import { Box, Card, CardContent, Grid2 as Grid, Typography } from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchDashboard } from '@/store/dashboardSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Dashboard home page.
 *
 * Renders 4 MUI Card components fed by GET /v1/admin/dashboard (via the BFF):
 *   - Transactions today          (count)
 *   - Approved volume today       (Money)
 *   - Active partners             (count)
 *   - Rolling failure rate        (percentage)
 *
 * UX states:
 *   - loading & no data -> Skeleton grid
 *   - error             -> ErrorAlert with retry button
 *   - data is null      -> EmptyState (Lottie + heading)
 *   - data present      -> Grid of 4 cards
 */
export default function DashboardPage() {
  const dispatch = useAppDispatch();
  const { data, loading, error } = useAppSelector((s) => s.dashboard);

  const reload = useCallback(() => {
    dispatch(fetchDashboard());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Dashboard
      </Typography>

      <ErrorAlert
        message={error}
        onRetry={reload}
        title="Could not load dashboard metrics"
      />

      {loading && !data ? (
        <Grid container spacing={2}>
          {[0, 1, 2, 3].map((i) => (
            <Grid key={i} size={{ xs: 12, sm: 6, md: 3 }}>
              <LoadingSkeleton variant="card" />
            </Grid>
          ))}
        </Grid>
      ) : null}

      {!data && !loading && !error ? (
        <Card variant="outlined">
          <EmptyState
            heading="No data yet"
            description="Once partners start transacting, KPIs will appear here."
          />
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
                <Typography variant="h3">
                  {data.txnCountToday.toLocaleString()}
                </Typography>
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

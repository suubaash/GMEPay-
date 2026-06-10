'use client';

import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  CircularProgress,
  Grid2 as Grid,
  Typography,
} from '@mui/material';
import { adminApi } from '@/api/client';
import type { RevenueSummary } from '@/api/types';
import MoneyDisplay from '@/components/MoneyDisplay';

/**
 * Revenue summary — skeleton from /v1/admin/revenue/summary.
 *
 * Surfaces rounding gain/loss separately because the per-partner rounding
 * residual is the central money-integrity invariant (MONEY_CONVENTION.md).
 */
export default function RevenuePage() {
  const [data, setData] = useState<RevenueSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    adminApi
      .fetchRevenueSummary()
      .then((d) => !cancelled && setData(d))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Revenue summary
      </Typography>
      {error ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          Revenue endpoint not available yet: {error}
        </Alert>
      ) : null}
      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      ) : data ? (
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Total revenue ({data.periodStart} -> {data.periodEnd})
                </Typography>
                <Typography variant="h3"><MoneyDisplay value={data.totalRevenue} /></Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Rounding gain
                </Typography>
                <Typography variant="h3"><MoneyDisplay value={data.totalRoundingGain} /></Typography>
              </CardContent>
            </Card>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <Card>
              <CardContent>
                <Typography variant="body2" color="text.secondary">
                  Rounding loss
                </Typography>
                <Typography variant="h3"><MoneyDisplay value={data.totalRoundingLoss} /></Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      ) : (
        <Typography color="text.secondary">No revenue data yet.</Typography>
      )}
    </Box>
  );
}

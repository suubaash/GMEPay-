'use client';

import { useCallback, useEffect } from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Grid2 as Grid,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchFlywheel } from '@/store/flywheelSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Flywheel page — the 7 growth-loop KPIs from docs/QR_HUB_GROWTH_FLYWHEEL.md §5,
 * fed by GET /v1/admin/flywheel (trailing 30 days by default).
 *
 * Every metric is nullable: null renders as an em dash (never a fake zero), and
 * the three ops-entered metrics carry a hint pointing at the flywheel.* keys in
 * Platform Settings. The reading: sides + volume should grow while the loop-health
 * rates (activation time, adapter time-to-live) shrink — that is compounding.
 */

/** "3 of 5" style live-vs-total renderer for the two network sides. */
function ratio(live, total) {
  if (live === null || live === undefined) return '—';
  return total === null || total === undefined ? String(live) : `${live} of ${total}`;
}

/** Plain nullable number → string with an em dash for "not yet measurable". */
function num(value, suffix = '') {
  if (value === null || value === undefined) return '—';
  return `${Number(value).toLocaleString()}${suffix}`;
}

function KpiCard({ label, children, hint }) {
  return (
    <Grid size={{ xs: 12, sm: 6, md: 3 }}>
      <Card sx={{ height: '100%' }}>
        <CardContent>
          <Typography variant="body2" color="text.secondary">
            {label}
          </Typography>
          <Typography variant="h3" component="div">
            {children}
          </Typography>
          {hint ? (
            <Typography variant="caption" color="text.secondary">
              {hint}
            </Typography>
          ) : null}
        </CardContent>
      </Card>
    </Grid>
  );
}

function Section({ title, children }) {
  return (
    <Box sx={{ mb: 3 }}>
      <Typography variant="h6" gutterBottom>
        {title}
      </Typography>
      <Grid container spacing={2}>
        {children}
      </Grid>
    </Box>
  );
}

const OPS_HINT = 'Ops-entered — set flywheel.* in Platform Settings';

export default function FlywheelPage() {
  const dispatch = useAppDispatch();
  const { data, loading, error } = useAppSelector((s) => s.flywheel);

  const reload = useCallback(() => {
    dispatch(fetchFlywheel());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  const network = data?.network;
  const volume = data?.volume;
  const capital = data?.capital;
  const loopHealth = data?.loopHealth;

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Flywheel
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Growth-loop KPIs (trailing 30 days) — sides and volume should grow while
        the loop-health rates shrink. See docs/QR_HUB_GROWTH_FLYWHEEL.md.
      </Typography>

      <ErrorAlert
        message={error}
        onRetry={reload}
        title="Could not load flywheel metrics"
      />

      {loading && !data ? (
        <Grid container spacing={2}>
          {[0, 1, 2, 3, 4, 5, 6, 7].map((i) => (
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
            description="Once partners and schemes go live, loop KPIs will appear here."
          />
        </Card>
      ) : null}

      {data ? (
        <>
          {volume?.tpvTruncated ? (
            <Alert severity="warning" sx={{ mb: 2 }} data-testid="tpv-truncated">
              TPV covers only the first{' '}
              {Number(volume.scannedTxnCount ?? 0).toLocaleString()} of{' '}
              {Number(volume.approvedTxnCount ?? 0).toLocaleString()} approved
              transactions in the window — treat it as a lower bound.
            </Alert>
          ) : null}

          <Section title="Network sides">
            <KpiCard label="Live sending wallets">
              {ratio(network?.liveWallets, network?.totalWallets)}
            </KpiCard>
            <KpiCard label="Live acceptance schemes">
              {ratio(network?.liveSchemes, network?.totalSchemes)}
            </KpiCard>
            <KpiCard label="Acceptance points" hint={OPS_HINT}>
              {num(network?.acceptancePoints)}
            </KpiCard>
          </Section>

          <Section title="Volume & economics">
            <KpiCard label="Cross-border TPV (USD)">
              <MoneyDisplay amount={volume?.tpvUsd} currency="USD" />
            </KpiCard>
            <KpiCard label="Revenue (USD)">
              <MoneyDisplay amount={volume?.revenueUsd} currency="USD" />
            </KpiCard>
            <KpiCard label="Blended take rate">
              {num(volume?.takeRatePct, '%')}
            </KpiCard>
            <KpiCard label="Approved transactions">
              {num(volume?.approvedTxnCount)}
            </KpiCard>
          </Section>

          <Section title="Capital efficiency">
            <KpiCard label="Total prefund (USD)">
              <MoneyDisplay amount={capital?.totalPrefundUsd} currency="USD" />
            </KpiCard>
            <KpiCard
              label="Prefunding turn ratio"
              hint="Window TPV ÷ current prefund — higher is better"
            >
              {num(capital?.prefundingTurnRatio, '×')}
            </KpiCard>
          </Section>

          <Section title="Loop health">
            <KpiCard
              label="Median time to first txn"
              hint={`${num(loopHealth?.activatedPartnerCount)} activated · ${num(
                loopHealth?.pendingPartnerCount
              )} pending`}
            >
              {num(loopHealth?.medianPartnerTimeToFirstTxnHours, 'h')}
            </KpiCard>
            <KpiCard label="Adapter time-to-live" hint={OPS_HINT}>
              {num(loopHealth?.adapterTimeToLiveDays, ' days')}
            </KpiCard>
            <KpiCard label="Monthly active payers" hint={OPS_HINT}>
              {num(loopHealth?.monthlyActivePayers)}
            </KpiCard>
            <KpiCard label="Txns per payer (window)">
              {num(loopHealth?.txnsPerPayer)}
            </KpiCard>
          </Section>
        </>
      ) : null}
    </Box>
  );
}

'use client';

import { useCallback, useEffect, useState } from 'react';
import { Box, Card, CardContent, Chip, Stack, Typography } from '@mui/material';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { adminApi } from '@/api/client';

/**
 * ActivationTile — loop-B health for one partner (docs/QR_HUB_GROWTH_FLYWHEEL.md,
 * tracker item #3): how long from onboarding to the first APPROVED transaction.
 *
 * Props:
 *   partnerCode  string  (matched against the delivery overview's activation rows)
 *
 * Backend contract: GET /v1/admin/delivery/overview ->
 *   activation: [{ partner, onboardedAt, firstApprovedAt, activationHours,
 *                  status: 'activated' | 'pending' }]
 * This tile picks this partner's row; an absent row renders as "no data yet".
 */

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/** "63h" under a day-ish, otherwise "2d 15h" — activation is a coarse metric. */
export function formatHours(hours) {
  if (hours === null || hours === undefined) return '—';
  const n = Number(hours);
  if (!Number.isFinite(n)) return '—';
  if (n < 48) return `${n}h`;
  return `${Math.floor(n / 24)}d ${n % 24}h`;
}

export default function ActivationTile({ partnerCode }) {
  const [row, setRow] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    adminApi
      .getDeliveryOverview()
      .then((overview) => {
        const match = (overview?.activation ?? []).find(
          (a) => a.partner === partnerCode
        );
        setRow(match ?? null);
      })
      .catch((e) => setError(e?.message ?? 'Failed to load activation data'))
      .finally(() => setLoading(false));
  }, [partnerCode]);

  useEffect(() => {
    if (partnerCode) reload();
  }, [partnerCode, reload]);

  return (
    <Card sx={{ maxWidth: 720 }} data-testid="activation-tile">
      <CardContent>
        <Stack spacing={2}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="h6">Activation</Typography>
            {row ? (
              <Chip
                size="small"
                label={row.status === 'activated' ? 'Activated' : 'Pending first txn'}
                color={row.status === 'activated' ? 'success' : 'warning'}
                data-testid="activation-status"
              />
            ) : null}
          </Box>
          <Typography variant="body2" color="text.secondary">
            Onboarding → first approved transaction (loop-B health: shorter is a
            healthier partner funnel).
          </Typography>

          <ErrorAlert
            message={error}
            onRetry={reload}
            title="Could not load activation data"
          />

          {loading ? <LoadingSkeleton variant="card" /> : null}

          {!loading && !error && !row ? (
            <Typography variant="body2" color="text.secondary">
              No activation data yet for this partner.
            </Typography>
          ) : null}

          {!loading && row ? (
            <Stack direction="row" spacing={4}>
              <Box>
                <Typography variant="body2" color="text.secondary">
                  Onboarded
                </Typography>
                <Typography data-testid="activation-onboarded">
                  {formatDate(row.onboardedAt)}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="text.secondary">
                  First approved txn
                </Typography>
                <Typography data-testid="activation-first-approved">
                  {formatDate(row.firstApprovedAt)}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="text.secondary">
                  Time to first txn
                </Typography>
                <Typography variant="h6" data-testid="activation-hours">
                  {formatHours(row.activationHours)}
                </Typography>
              </Box>
            </Stack>
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  );
}

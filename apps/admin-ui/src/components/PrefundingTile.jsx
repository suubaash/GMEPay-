'use client';

import { useCallback, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Chip,
  LinearProgress,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import MoneyDisplay from '@/components/MoneyDisplay';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchBalance, fetchBalanceAlerts } from '@/store/balanceSlice';

/**
 * PrefundingTile — live prefunding balance gauge + recent alerts.
 *
 * Props:
 *   partnerCode  string  (the partnerCode used as the key for both API calls)
 *
 * Backend contract (Slice 5B.1):
 *   GET /v1/admin/partners/{code}/balance
 *     -> { currency, balance (BigDecimal string), threshold (BigDecimal string), pctOfThreshold (number) }
 *
 *   GET /v1/admin/partners/{code}/balance-alerts
 *     -> BalanceAlertView[] { tier, balanceUsd, thresholdUsd, raisedAt, acknowledged }
 *
 * Gauge colour zones (by pctOfThreshold — balance as % of threshold):
 *   >= 95%  green   (success)
 *   70-<95% amber   (warning)
 *   <  70%  red     (error)
 */

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * Returns the MUI LinearProgress color and a descriptive label for a given
 * pctOfThreshold value.
 *
 * pctOfThreshold is the balance expressed as a percentage of the configured
 * low-balance threshold (NOT a percentage of some max capacity).
 * Colour mapping:
 *   >= 95 → green (success) — well-funded
 *   70-94 → amber (warning) — approaching threshold
 *   <  70 → red (error)     — below or near critical threshold
 */
export function gaugeColor(pct) {
  if (pct == null || pct === undefined) return 'primary';
  const n = Number(pct);
  if (n >= 95) return 'success';
  if (n >= 70) return 'warning';
  return 'error';
}

/**
 * Gauge label text for the balance zone.
 */
export function gaugeLabel(pct) {
  if (pct == null || pct === undefined) return '—';
  const n = Number(pct);
  if (n >= 95) return 'Well funded';
  if (n >= 70) return 'Approaching threshold';
  return 'Below threshold';
}

function TierChip({ tier }) {
  const color = tier === 'CRITICAL' ? 'error' : 'warning';
  return <Chip label={tier} color={color} size="small" variant="filled" />;
}

function AlertRow({ alert }) {
  return (
    <Stack
      direction="row"
      spacing={1}
      alignItems="center"
      sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider' }}
      data-testid="alert-row"
    >
      <TierChip tier={alert.tier} />
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2" color="text.secondary" noWrap>
          {formatDate(alert.raisedAt)}
        </Typography>
        <Stack direction="row" spacing={1}>
          <Typography variant="caption" color="text.secondary">
            Bal:
          </Typography>
          <MoneyDisplay amount={alert.balanceUsd} currency="USD" />
          <Typography variant="caption" color="text.secondary">
            / Threshold:
          </Typography>
          <MoneyDisplay amount={alert.thresholdUsd} currency="USD" />
        </Stack>
      </Box>
      {alert.acknowledged && (
        <Tooltip title="Acknowledged">
          <CheckCircleOutlineIcon
            fontSize="small"
            color="success"
            data-testid="ack-icon"
            aria-label="acknowledged"
          />
        </Tooltip>
      )}
    </Stack>
  );
}

export default function PrefundingTile({ partnerCode }) {
  const dispatch = useAppDispatch();

  const balanceData = useAppSelector((s) => s.balance.byCode[partnerCode]);
  const alerts = useAppSelector((s) => s.balance.alertsByCode[partnerCode] ?? []);
  const loading = useAppSelector((s) => s.balance.loading[partnerCode] ?? false);
  const alertsLoading = useAppSelector((s) => s.balance.alertsLoading[partnerCode] ?? false);
  const error = useAppSelector((s) => s.balance.error);

  const reload = useCallback(() => {
    if (!partnerCode) return;
    dispatch(fetchBalance(partnerCode));
    dispatch(fetchBalanceAlerts(partnerCode));
  }, [dispatch, partnerCode]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (loading && !balanceData) {
    return <LoadingSkeleton variant="page" />;
  }

  const pct = balanceData?.pctOfThreshold;
  const gaugeValue = pct != null ? Math.min(100, Math.max(0, Number(pct))) : 0;
  const color = gaugeColor(pct);
  const label = gaugeLabel(pct);

  return (
    <Box>
      <ErrorAlert message={error} onRetry={reload} title="Could not load prefunding data" />

      <Card sx={{ maxWidth: 720, mb: 2 }} data-testid="prefunding-tile">
        <CardContent>
          <Typography variant="subtitle1" gutterBottom>
            Prefunding Balance
          </Typography>

          {/* Balance figure */}
          <Stack direction="row" spacing={1} alignItems="baseline" sx={{ mb: 2 }}>
            <Typography variant="h4" component="span" sx={{ fontVariantNumeric: 'tabular-nums' }}>
              <MoneyDisplay
                amount={balanceData?.balance}
                currency={balanceData?.currency}
                withCurrency={false}
              />
            </Typography>
            {balanceData?.currency && (
              <Typography variant="h6" component="span" color="text.secondary">
                {balanceData.currency}
              </Typography>
            )}
          </Stack>

          {/* Threshold gauge */}
          <Box sx={{ mb: 1 }}>
            <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">
                {pct != null ? `${Number(pct).toFixed(1)}% of threshold` : 'Threshold gauge'}
              </Typography>
              <Chip
                label={label}
                color={color}
                size="small"
                variant="outlined"
                data-testid="gauge-chip"
              />
            </Stack>
            <LinearProgress
              variant="determinate"
              value={gaugeValue}
              color={color}
              sx={{ height: 10, borderRadius: 1 }}
              aria-label="balance as percentage of threshold"
              data-testid="balance-gauge"
            />
            <Stack direction="row" justifyContent="space-between" sx={{ mt: 0.5 }}>
              <Typography variant="caption" color="text.secondary">
                0
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Threshold:{' '}
                <MoneyDisplay
                  amount={balanceData?.threshold}
                  currency={balanceData?.currency}
                />
              </Typography>
            </Stack>
          </Box>
        </CardContent>
      </Card>

      {/* Alerts list */}
      <Card sx={{ maxWidth: 720 }} data-testid="alerts-card">
        <CardContent>
          <Typography variant="subtitle1" gutterBottom>
            Recent Balance Alerts
          </Typography>
          {alertsLoading && <LoadingSkeleton variant="table" rows={3} />}
          {!alertsLoading && alerts.length === 0 && (
            <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
              No alerts.
            </Typography>
          )}
          {!alertsLoading &&
            alerts.map((alert, idx) => (
              <AlertRow key={alert.raisedAt ?? idx} alert={alert} />
            ))}
        </CardContent>
      </Card>
    </Box>
  );
}

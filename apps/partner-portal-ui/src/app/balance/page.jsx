'use client';
import * as React from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  Divider,
  Grid,
  List,
  ListItem,
  ListItemText,
  Stack,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import dynamic from 'next/dynamic';
import { fetchBalance } from '@/store/balanceSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';

/**
 * Balance page — UC-10-01.
 *
 * Wire shape (BalanceView):
 *   {
 *     partnerCode: string,
 *     currency:    string,
 *     balance:     string,          // BigDecimal-as-string
 *     threshold:   string,          // BigDecimal-as-string
 *     pctOfThreshold: string|null,  // BigDecimal-as-string; balance/threshold*100, scale-2
 *     recentDeductions: Array<{
 *       amountUsd: string,          // BigDecimal-as-string, USD, scale-8
 *       at:        string,          // ISO instant (display in KST)
 *       txnRef:    string
 *     }>|null                       // newest-first; null until prefunding wires it
 *   }
 *
 * Legacy BFF wire also carries { partnerId, lowBalanceThreshold } for backward compat;
 * this page accepts both shapes defensively.
 *
 * Money MUST NOT be cast to JS Number. `balance` and `threshold` are decimal
 * strings — use Number() only for the isHealthy comparisons (display only, not
 * for financial arithmetic).
 */

// Lottie is browser-only — import lazily.
const Lottie = dynamic(() => import('lottie-react'), { ssr: false });

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
        { ty: 'el', d: 1, s: { a: 0, k: [120, 120] }, p: { a: 0, k: [0, 0] } },
        { ty: 'fl', c: { a: 0, k: [0.086, 0.639, 0.290, 1] }, o: { a: 0, k: 100 } }
      ],
      ip: 0,
      op: 60,
      st: 0,
      bm: 0
    }
  ],
  markers: []
};

/**
 * Resolve the threshold from either BalanceView (UC-10) or the legacy BFF
 * shape (lowBalanceThreshold). Returns null if absent.
 */
function resolveThreshold(data) {
  if (!data) return null;
  // UC-10-01 BalanceView uses `threshold`; legacy BFF used `lowBalanceThreshold`
  return data.threshold ?? data.lowBalanceThreshold ?? null;
}

function isHealthy(balanceAmount, thresholdAmount) {
  const b = Number(balanceAmount);
  const t = Number(thresholdAmount);
  if (!Number.isFinite(b) || !Number.isFinite(t)) return false;
  return b > t;
}

function isCelebratory(balanceAmount, thresholdAmount) {
  const b = Number(balanceAmount);
  const t = Number(thresholdAmount);
  if (!Number.isFinite(b) || !Number.isFinite(t)) return false;
  return b > t * 3;
}

/**
 * Display a UTC ISO instant in KST (Asia/Seoul, UTC+9).
 * Falls back to browser locale if the Intl API is unavailable.
 */
function toKST(isoInstant) {
  if (!isoInstant) return '—';
  try {
    return new Date(isoInstant).toLocaleString('en-GB', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }) + ' KST';
  } catch {
    return new Date(isoInstant).toLocaleString();
  }
}

export default function BalancePage() {
  const dispatch = useDispatch();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s) => s.balance);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchBalance(partnerId));
  }, [partnerId, status, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchBalance(partnerId));
  }, [partnerId, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  const currency = data?.currency ?? '';
  const balanceAmount = data?.balance ?? '0';
  const thresholdAmount = resolveThreshold(data) ?? '0';
  const pctOfThreshold = data?.pctOfThreshold ?? null;
  const recentDeductions = Array.isArray(data?.recentDeductions) ? data.recentDeductions : null;

  // Status chip: Healthy / Low / Unknown
  function balanceStatusChip(balAmt, thr) {
    const hasThreshold = thr && thr !== '0';
    if (!hasThreshold) return <Chip label="No threshold set" color="default" />;
    if (isCelebratory(balAmt, thr)) return <Chip label="Healthy" color="success" />;
    if (isHealthy(balAmt, thr)) return <Chip label="Healthy" color="success" />;
    return <Chip label="Below threshold" color="warning" />;
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Balance</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Your current prefunding balance and low-balance threshold.
        </Typography>
      </Box>

      {status === 'loading' && <LoadingSkeleton variant="detail" />}
      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load balance.'} onRetry={retry} />
      )}

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
                      <MoneyDisplay
                        amount={balanceAmount}
                        currency={currency}
                        parenthesizeNegative
                        showRawTooltip
                      />
                    </Typography>
                  </Box>

                  <Divider flexItem />

                  <Stack direction="row" spacing={3} flexWrap="wrap">
                    <Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Low-balance threshold
                      </Typography>
                      <Typography variant="h4">
                        <MoneyDisplay amount={thresholdAmount} currency={currency} />
                      </Typography>
                    </Box>
                    {pctOfThreshold != null && (
                      <Box>
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          % of threshold
                        </Typography>
                        <Typography variant="h4" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                          {pctOfThreshold}%
                        </Typography>
                      </Box>
                    )}
                    <Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Status
                      </Typography>
                      <Box sx={{ mt: 0.5 }} data-testid="balance-status-chip">
                        {balanceStatusChip(balanceAmount, thresholdAmount)}
                      </Box>
                    </Box>
                    <Box>
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        Partner
                      </Typography>
                      <Typography variant="body1">{data.partnerCode ?? data.partnerId ?? '—'}</Typography>
                    </Box>
                  </Stack>
                </Stack>
              </Grid>

              <Grid item xs={12} md={4} sx={{ textAlign: 'center' }}>
                {isCelebratory(balanceAmount, thresholdAmount) ? (
                  <Box sx={{ width: 200, height: 200, mx: 'auto' }} data-testid="balance-celebration">
                    <Lottie animationData={HEALTHY_PULSE_LOTTIE} loop autoplay />
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      Balance is comfortably healthy
                    </Typography>
                  </Box>
                ) : isHealthy(balanceAmount, thresholdAmount) ? (
                  <Alert severity="info">
                    Balance is above threshold. Consider topping up when you approach 3× the
                    threshold for a comfortable margin.
                  </Alert>
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

      {/* UC-10-01: Recent deduction history */}
      {recentDeductions && recentDeductions.length > 0 && (
        <Card>
          <CardContent>
            <Typography variant="h3" sx={{ mb: 1 }}>
              Recent deductions
            </Typography>
            <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
              Most recent prefunding deductions, newest first. Times are in KST.
            </Typography>
            <List dense data-testid="deduction-history-list">
              {recentDeductions.map((d, idx) => (
                <React.Fragment key={`${d.txnRef}-${idx}`}>
                  {idx > 0 && <Divider component="li" />}
                  <ListItem alignItems="flex-start">
                    <ListItemText
                      primary={
                        <Stack direction="row" spacing={2} alignItems="center">
                          <MoneyDisplay amount={d.amountUsd} currency="USD" showRawTooltip />
                          <Typography
                            component="span"
                            variant="caption"
                            sx={{ fontFamily: 'monospace', color: 'text.secondary' }}
                          >
                            {d.txnRef}
                          </Typography>
                        </Stack>
                      }
                      secondary={toKST(d.at)}
                    />
                  </ListItem>
                </React.Fragment>
              ))}
            </List>
          </CardContent>
        </Card>
      )}

      {recentDeductions && recentDeductions.length === 0 && (
        <Card>
          <CardContent>
            <Typography variant="h3" sx={{ mb: 1 }}>Recent deductions</Typography>
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              No deductions recorded yet.
            </Typography>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

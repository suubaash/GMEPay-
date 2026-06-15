'use client';
import * as React from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Grid,
  Stack,
  Step,
  StepContent,
  StepLabel,
  Stepper,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import { clearDetail, fetchTransactionDetail } from '@/store/transactionsSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import Breadcrumbs from '@/components/Breadcrumbs';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Transaction detail page — UC-10-03.
 *
 * Wire shape (TransactionDetail):
 *   {
 *     summary: {
 *       txnId, partnerId, state, amount, currency, committedAt,
 *       // UC-10-02 additive:
 *       qrSchemeId, krwAmount, payerCurrency, payerCurrencyAmount,
 *       appliedFxRate, rateTimestamp, prefundingDeductedUsd
 *     },
 *     schemeTxnRef:       string,
 *     schemeApprovalCode: string,
 *     prefundDeductedUsd: string,      // BigDecimal-as-string
 *     approvedAt:         string|null, // ISO instant
 *     bookedSettlementAmount: string,
 *     settlementRoundingMode: string,
 *     roundingResidual: string,
 *     // UC-10-03 additive:
 *     merchantId:    string|null,
 *     merchantName:  string|null,
 *     statusHistory: Array<{ status: string, at: string }>|null  // oldest-first
 *   }
 *
 * Internal revenue (FX margin, GME revenue) is stripped by the BFF — never
 * rendered here.
 *
 * Money MUST NOT be cast to JS Number. Render decimal strings via <MoneyDisplay />.
 *
 * On 404 we redirect to /transactions with a toast.
 */
export default function TransactionDetailPage() {
  const params = useParams();
  const router = useRouter();
  const dispatch = useDispatch();
  const snackbar = useSnackbar();
  const partnerId = currentPartnerId();
  const rawTxnId = params?.txnId ?? '';
  const txnId = Array.isArray(rawTxnId) ? rawTxnId[0] : rawTxnId;
  const decodedTxnId = txnId ? decodeURIComponent(txnId) : '';

  const { data, status, error, failureStatus } = useSelector(
    (s) => s.transactions.detail
  );

  React.useEffect(() => {
    if (!partnerId || !decodedTxnId) return;
    dispatch(fetchTransactionDetail({ partnerId, txnId: decodedTxnId }));
    return () => {
      dispatch(clearDetail());
    };
  }, [partnerId, decodedTxnId, dispatch]);

  // 404 -> redirect to /transactions with a toast.
  React.useEffect(() => {
    if (status === 'failed' && failureStatus === 404) {
      snackbar.showError(`Transaction "${decodedTxnId}" not found.`);
      router.replace('/transactions');
    }
  }, [status, failureStatus, decodedTxnId, router, snackbar]);

  const retry = React.useCallback(() => {
    if (partnerId && decodedTxnId) {
      dispatch(fetchTransactionDetail({ partnerId, txnId: decodedTxnId }));
    }
  }, [partnerId, decodedTxnId, dispatch]);

  const summary = data?.summary ?? null;
  const currency = summary?.currency ?? '';
  const amount = summary?.amount ?? '0';
  const state = summary?.state ?? '';
  const committedAt = summary?.committedAt ?? null;
  const approvedAt = data?.approvedAt ?? null;

  // UC-10-03 fields
  const krwAmount = summary?.krwAmount ?? null;
  const payerCurrency = summary?.payerCurrency ?? null;
  const payerCurrencyAmount = summary?.payerCurrencyAmount ?? null;
  const appliedFxRate = summary?.appliedFxRate ?? null;
  const rateTimestamp = summary?.rateTimestamp ?? null;
  const prefundingDeductedUsd = summary?.prefundingDeductedUsd ?? data?.prefundDeductedUsd ?? null;
  const merchantId = data?.merchantId ?? null;
  const merchantName = data?.merchantName ?? null;
  const statusHistory = Array.isArray(data?.statusHistory) ? data.statusHistory : null;

  return (
    <Stack spacing={3}>
      <Box>
        <Breadcrumbs
          items={[
            { label: 'Overview', href: '/' },
            { label: 'Transactions', href: '/transactions' },
            { label: decodedTxnId || 'Detail' }
          ]}
        />
        <Button component={Link} href="/transactions" size="small" sx={{ mb: 1 }}>
          ← Back to transactions
        </Button>
        <Typography variant="h1">Transaction {decodedTxnId}</Typography>
      </Box>

      {status === 'loading' && <LoadingSkeleton variant="detail" />}
      {status === 'failed' && failureStatus !== 404 && (
        <ErrorAlert message={error ?? 'Failed to load transaction.'} onRetry={retry} />
      )}

      {data && summary && (
        <>
          {/* Summary card */}
          <Card>
            <CardContent>
              <Grid container spacing={3}>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    State
                  </Typography>
                  <Box sx={{ mt: 0.5 }}>
                    <StatusChip status={state} size="medium" />
                  </Box>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Committed
                  </Typography>
                  <Typography>
                    {committedAt ? new Date(committedAt).toLocaleString() : '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Approved
                  </Typography>
                  <Typography>
                    {approvedAt ? new Date(approvedAt).toLocaleString() : '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Amount
                  </Typography>
                  <Typography variant="h4">
                    <MoneyDisplay amount={amount} currency={currency} showRawTooltip />
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 3 }} />

              {/* UC-10-03: Merchant info */}
              <Grid container spacing={3}>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Merchant ID
                  </Typography>
                  <Typography sx={{ fontFamily: 'monospace' }}>
                    {merchantId ?? '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Merchant name
                  </Typography>
                  <Typography>{merchantName ?? '—'}</Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    QR scheme
                  </Typography>
                  <Box sx={{ mt: 0.5 }}>
                    {summary?.qrSchemeId
                      ? <Chip label={summary.qrSchemeId} size="small" variant="outlined" />
                      : <Typography>—</Typography>}
                  </Box>
                </Grid>
              </Grid>

              <Divider sx={{ my: 3 }} />

              {/* UC-10-03: KRW + payer-ccy amounts, FX rate */}
              <Grid container spacing={3}>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    KRW amount
                  </Typography>
                  <Typography>
                    {krwAmount != null
                      ? <MoneyDisplay amount={krwAmount} currency="KRW" showRawTooltip />
                      : '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Payer amount
                  </Typography>
                  <Typography>
                    {payerCurrencyAmount != null && payerCurrency
                      ? <MoneyDisplay amount={payerCurrencyAmount} currency={payerCurrency} showRawTooltip />
                      : '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Applied FX rate
                  </Typography>
                  <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {appliedFxRate != null ? appliedFxRate : '—'}
                  </Typography>
                  {rateTimestamp && (
                    <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block' }}>
                      Rate locked: {new Date(rateTimestamp).toLocaleString()}
                    </Typography>
                  )}
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Prefunding deducted
                  </Typography>
                  <Typography>
                    {prefundingDeductedUsd != null
                      ? <MoneyDisplay amount={prefundingDeductedUsd} currency="USD" showRawTooltip />
                      : '—'}
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 3 }} />

              {/* Original settlement fields */}
              <Grid container spacing={3}>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Scheme txn ref
                  </Typography>
                  <Typography sx={{ fontFamily: 'monospace' }}>
                    {data.schemeTxnRef ?? '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Scheme approval code
                  </Typography>
                  <Typography sx={{ fontFamily: 'monospace' }}>
                    {data.schemeApprovalCode ?? '—'}
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Booked settlement
                  </Typography>
                  <Typography>
                    <MoneyDisplay
                      amount={data.bookedSettlementAmount ?? '0'}
                      currency={currency}
                      showRawTooltip
                    />
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Rounding residual
                  </Typography>
                  <Typography>
                    <MoneyDisplay
                      amount={data.roundingResidual ?? '0'}
                      currency={currency}
                      parenthesizeNegative
                      showRawTooltip
                    />
                  </Typography>
                </Grid>

                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Rounding mode
                  </Typography>
                  <Typography>{data.settlementRoundingMode ?? '—'}</Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>

          {/* UC-10-03: Status history timeline */}
          {statusHistory && statusHistory.length > 0 && (
            <Card>
              <CardContent>
                <Typography variant="h3" sx={{ mb: 2 }}>
                  Status history
                </Typography>
                <Stepper orientation="vertical" data-testid="status-history-stepper">
                  {statusHistory.map((entry, idx) => (
                    <Step key={`${entry.status}-${idx}`} active completed={idx < statusHistory.length - 1}>
                      <StepLabel>
                        <StatusChip status={entry.status} />
                      </StepLabel>
                      <StepContent>
                        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                          {entry.at ? new Date(entry.at).toLocaleString() : '—'}
                        </Typography>
                      </StepContent>
                    </Step>
                  ))}
                </Stepper>
              </CardContent>
            </Card>
          )}
        </>
      )}
    </Stack>
  );
}

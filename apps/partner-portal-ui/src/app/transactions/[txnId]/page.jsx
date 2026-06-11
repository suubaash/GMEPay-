'use client';
import * as React from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  Stack,
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
 * Transaction detail page.
 *
 * Wire shape (TransactionDetail):
 *   {
 *     summary: { txnId, partnerId, state, amount, currency, committedAt },
 *     schemeTxnRef:       string,
 *     schemeApprovalCode: string,
 *     prefundDeductedUsd: string,      // BigDecimal-as-string
 *     approvedAt:         string|null, // ISO instant
 *     bookedSettlementAmount: string,
 *     settlementRoundingMode: string,
 *     roundingResidual: string
 *   }
 *
 * The settlement currency is the same as the summary currency on the wire;
 * residual + booked amount inherit it.
 *
 * On 404 we redirect to /transactions with a toast (BFF returns 404 both for
 * unknown IDs and IDs that belong to a different partner — see
 * PartnerPortalController#transactionDetail).
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
                    Prefund deducted
                  </Typography>
                  <Typography>
                    <MoneyDisplay
                      amount={data.prefundDeductedUsd ?? '0'}
                      currency="USD"
                      showRawTooltip
                    />
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
                    Rounding mode
                  </Typography>
                  <Typography>{data.settlementRoundingMode ?? '—'}</Typography>
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
              </Grid>
            </CardContent>
          </Card>
        </>
      )}
    </Stack>
  );
}

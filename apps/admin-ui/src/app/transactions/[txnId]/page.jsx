'use client';

import { useCallback, useEffect } from 'react';
import { useParams } from 'next/navigation';
import {
  Box,
  Card,
  CardContent,
  Divider,
  Grid2 as Grid,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { getTransaction } from '@/store/transactionsSlice';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';

/**
 * Transaction detail page.
 *
 * GET /v1/admin/transactions/{txnId} -> TransactionDetail
 *   { summary: TransactionSummary,
 *     schemeTxnRef, schemeApprovalCode,
 *     prefundDeductedUsd, approvedAt,
 *     bookedSettlementAmount, settlementRoundingMode, roundingResidual }
 *
 * Where TransactionSummary is:
 *   { txnId, partnerId, state, amount (string), currency, committedAt }
 *
 * The "Settlement rounding lock" card surfaces the per-partner rounding
 * residual posted to REVENUE_ROUNDING (docs/MONEY_CONVENTION.md).
 */
function Field({ label, children }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Box sx={{ mt: 0.5 }}>{children}</Box>
    </Box>
  );
}

export default function TransactionDetailPage() {
  const params = useParams();
  const id = params?.txnId;
  const dispatch = useAppDispatch();
  const { details, detailLoading, error } = useAppSelector((s) => s.transactions);
  const txn = id ? details[id] : undefined;

  const reload = useCallback(() => {
    if (id) dispatch(getTransaction(id));
  }, [dispatch, id]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (error && !txn) {
    return <ErrorAlert message={error} onRetry={reload} title="Could not load transaction" />;
  }
  if (!txn) {
    return <LoadingSkeleton variant="page" />;
  }

  const summary = txn.summary ?? {};
  const currency = summary.currency ?? '';
  const settlementCurrency = currency; // BFF books the settlement in the txn currency

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        {summary.txnId ?? id}
      </Typography>
      <ErrorAlert message={detailLoading ? null : error} onRetry={reload} />

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardContent>
              <Typography variant="h4" gutterBottom>
                Overview
              </Typography>
              <Grid container spacing={2}>
                <Grid size={6}>
                  <Field label="Partner">
                    <Typography>{summary.partnerId ?? '—'}</Typography>
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Scheme txn ref">
                    <Typography>{txn.schemeTxnRef ?? '—'}</Typography>
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="State">
                    <StatusChip state={summary.state} />
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Scheme approval code">
                    <Typography>{txn.schemeApprovalCode ?? '—'}</Typography>
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Committed">
                    <Typography>{summary.committedAt ?? '—'}</Typography>
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Approved">
                    <Typography>{txn.approvedAt ?? '—'}</Typography>
                  </Field>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardContent>
              <Typography variant="h4" gutterBottom>
                Amounts
              </Typography>
              <Grid container spacing={2}>
                <Grid size={12}>
                  <Field label="Transaction amount">
                    <MoneyDisplay amount={summary.amount} currency={currency} />
                  </Field>
                </Grid>
                <Grid size={12}>
                  <Field label="Prefund deducted (USD)">
                    <MoneyDisplay
                      amount={txn.prefundDeductedUsd}
                      currency="USD"
                    />
                  </Field>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={12}>
          <Card>
            <CardContent>
              <Typography variant="h4" gutterBottom>
                Settlement rounding lock
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                The amount booked to the partner, the rounding mode applied at
                commit, and the residual posted to REVENUE_ROUNDING. See
                docs/MONEY_CONVENTION.md.
              </Typography>
              <Divider sx={{ mb: 2 }} />
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <Field label="Booked amount">
                    <MoneyDisplay
                      amount={txn.bookedSettlementAmount}
                      currency={settlementCurrency}
                    />
                  </Field>
                </Grid>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <Field label="Rounding mode">
                    <Typography>{txn.settlementRoundingMode ?? '—'}</Typography>
                  </Field>
                </Grid>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <Field label="Residual (precise − booked)">
                    <MoneyDisplay
                      amount={txn.roundingResidual}
                      currency={settlementCurrency}
                    />
                  </Field>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}

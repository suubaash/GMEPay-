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
 * Renders every field returned by GET /v1/admin/transactions/{id}, with
 * dedicated emphasis on the per-partner settlement rounding lock:
 *
 *   settlementBookedAmount   <- what the partner sees on their reconciliation
 *   settlementRoundingMode   <- rate-locked at commit
 *   settlementResidual       <- precise - booked, posted to REVENUE_ROUNDING
 *
 * See docs/MONEY_CONVENTION.md.
 */
function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
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
  const params = useParams<{ txnId: string }>();
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

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        {txn.id}
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
                    <Typography>{txn.partnerId}</Typography>
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Scheme">
                    <Typography>{txn.schemeId}</Typography>
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Status">
                    <StatusChip status={txn.status} />
                  </Field>
                </Grid>
                <Grid size={6}>
                  <Field label="Created">
                    <Typography>{txn.createdAt}</Typography>
                  </Field>
                </Grid>
                {txn.approvedAt ? (
                  <Grid size={6}>
                    <Field label="Approved">
                      <Typography>{txn.approvedAt}</Typography>
                    </Field>
                  </Grid>
                ) : null}
                {txn.settledAt ? (
                  <Grid size={6}>
                    <Field label="Settled">
                      <Typography>{txn.settledAt}</Typography>
                    </Field>
                  </Grid>
                ) : null}
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
                  <Field label="Send amount">
                    <MoneyDisplay value={txn.sendAmount} />
                  </Field>
                </Grid>
                <Grid size={12}>
                  <Field label="Collection amount">
                    <MoneyDisplay value={txn.collectionAmount} />
                  </Field>
                </Grid>
                {txn.payoutAmount ? (
                  <Grid size={12}>
                    <Field label="Payout amount">
                      <MoneyDisplay value={txn.payoutAmount} />
                    </Field>
                  </Grid>
                ) : null}
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
                    {txn.settlementBookedAmount ? (
                      <MoneyDisplay value={txn.settlementBookedAmount} />
                    ) : (
                      <Typography color="text.secondary">Pending settlement</Typography>
                    )}
                  </Field>
                </Grid>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <Field label="Rounding mode">
                    <Typography>{txn.settlementRoundingMode ?? '—'}</Typography>
                  </Field>
                </Grid>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <Field label="Residual (precise − booked)">
                    {txn.settlementResidual ? (
                      <MoneyDisplay value={txn.settlementResidual} />
                    ) : (
                      <Typography color="text.secondary">—</Typography>
                    )}
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

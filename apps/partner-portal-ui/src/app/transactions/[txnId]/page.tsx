'use client';
import * as React from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography
} from '@mui/material';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from '@/store';
import {
  clearDetail,
  fetchTransactionDetail
} from '@/store/transactionsSlice';
import { currentPartnerId } from '@/api/client';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import { useSnackbar } from '@/components/SnackbarProvider';

export default function TransactionDetailPage() {
  const params = useParams<{ txnId: string }>();
  const router = useRouter();
  const dispatch = useDispatch<AppDispatch>();
  const snackbar = useSnackbar();
  const partnerId = currentPartnerId();
  const txnId = decodeURIComponent(params.txnId);

  const { data, status, error, failureStatus } = useSelector(
    (s: RootState) => s.transactions.detail
  );

  React.useEffect(() => {
    if (!partnerId || !txnId) return;
    dispatch(fetchTransactionDetail({ partnerId, txnId }));
    return () => {
      dispatch(clearDetail());
    };
  }, [partnerId, txnId, dispatch]);

  // 404 -> redirect to /transactions with an error toast.
  React.useEffect(() => {
    if (status === 'failed' && failureStatus === 404) {
      snackbar.showError(`Transaction "${txnId}" not found.`);
      router.replace('/transactions');
    }
  }, [status, failureStatus, txnId, router, snackbar]);

  const retry = React.useCallback(() => {
    if (partnerId && txnId) {
      dispatch(fetchTransactionDetail({ partnerId, txnId }));
    }
  }, [partnerId, txnId, dispatch]);

  return (
    <Stack spacing={3}>
      <Box>
        <Button component={Link} href="/transactions" size="small" sx={{ mb: 1 }}>
          ← Back to transactions
        </Button>
        <Typography variant="h1">Transaction {txnId}</Typography>
      </Box>

      {status === 'loading' && <LoadingSkeleton variant="detail" />}
      {/*
        404 is handled by the redirect effect above, so we don't render an
        error surface for it (the toast does that work).
      */}
      {status === 'failed' && failureStatus !== 404 && (
        <ErrorAlert message={error ?? 'Failed to load transaction.'} onRetry={retry} />
      )}

      {data && (
        <>
          <Card>
            <CardContent>
              <Grid container spacing={3}>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Status
                  </Typography>
                  <Box sx={{ mt: 0.5 }}>
                    <StatusChip status={data.status} size="medium" />
                  </Box>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Created
                  </Typography>
                  <Typography>{new Date(data.createdAt).toLocaleString()}</Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Send
                  </Typography>
                  <Typography variant="h4">
                    <MoneyDisplay money={data.sendAmount} showRawTooltip />
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Payout
                  </Typography>
                  <Typography variant="h4">
                    <MoneyDisplay money={data.payoutAmount} showRawTooltip />
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 3 }} />

              <Grid container spacing={3}>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Rate (locked)
                  </Typography>
                  <Typography sx={{ fontFamily: 'monospace' }}>{data.rate}</Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Booked settlement
                  </Typography>
                  <Typography>
                    <MoneyDisplay money={data.bookedSettlementAmount} showRawTooltip />
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Rounding mode
                  </Typography>
                  <Typography>{data.settlementRoundingMode}</Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Rounding residual
                  </Typography>
                  <Typography>
                    <MoneyDisplay
                      money={data.roundingResidual}
                      parenthesizeNegative
                      showRawTooltip
                    />
                  </Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="h3" sx={{ mb: 2 }}>
                Event trail
              </Typography>
              {data.events.length === 0 ? (
                <Alert severity="info">No events recorded for this transaction yet.</Alert>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>At</TableCell>
                        <TableCell>Type</TableCell>
                        <TableCell>Detail</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {data.events.map((e, i) => (
                        <TableRow key={`${e.at}-${i}`}>
                          <TableCell>{new Date(e.at).toLocaleString()}</TableCell>
                          <TableCell>{e.type}</TableCell>
                          <TableCell>{e.detail ?? ''}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </Stack>
  );
}

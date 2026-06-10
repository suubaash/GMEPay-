'use client';
import * as React from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
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
import { portalApi, currentPartnerId } from '@/api/client';
import type { TransactionDetailDto } from '@/api/types';
import MoneyDisplay from '@/components/MoneyDisplay';
import StatusChip from '@/components/StatusChip';

export default function TransactionDetailPage() {
  const params = useParams<{ txnId: string }>();
  const partnerId = currentPartnerId();
  const txnId = decodeURIComponent(params.txnId);

  const [data, setData] = React.useState<TransactionDetailDto | null>(null);
  const [status, setStatus] = React.useState<'idle' | 'loading' | 'succeeded' | 'failed'>(
    'idle'
  );
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!partnerId || !txnId) return;
    setStatus('loading');
    portalApi
      .getTransaction(partnerId, txnId)
      .then((d) => {
        setData(d);
        setStatus('succeeded');
      })
      .catch((e: Error) => {
        setError(e.message);
        setStatus('failed');
      });
  }, [partnerId, txnId]);

  return (
    <Stack spacing={3}>
      <Box>
        <Button component={Link} href="/transactions" size="small" sx={{ mb: 1 }}>
          ← Back to transactions
        </Button>
        <Typography variant="h1">Transaction {txnId}</Typography>
      </Box>

      {status === 'loading' && <CircularProgress />}
      {status === 'failed' && <Alert severity="error">{error}</Alert>}

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
                    <MoneyDisplay money={data.sendAmount} />
                  </Typography>
                </Grid>
                <Grid item xs={12} md={3}>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Payout
                  </Typography>
                  <Typography variant="h4">
                    <MoneyDisplay money={data.payoutAmount} />
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
                    <MoneyDisplay money={data.bookedSettlementAmount} />
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
                    <MoneyDisplay money={data.roundingResidual} parenthesizeNegative />
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
            </CardContent>
          </Card>
        </>
      )}
    </Stack>
  );
}

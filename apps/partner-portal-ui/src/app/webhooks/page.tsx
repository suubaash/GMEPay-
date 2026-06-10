'use client';
import * as React from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Card,
  CardContent,
  Chip,
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
import { fetchWebhooks } from '@/store/webhooksSlice';
import { currentPartnerId } from '@/api/client';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';

export default function WebhooksPage() {
  const dispatch = useDispatch<AppDispatch>();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s: RootState) => s.webhooks);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchWebhooks(partnerId));
  }, [partnerId, status, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchWebhooks(partnerId));
  }, [partnerId, dispatch]);

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id available. Sign in or set <code>NEXT_PUBLIC_PARTNER_ID</code>.
      </Alert>
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Webhooks</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only listing of webhook subscriptions delivered by{' '}
          <code>notification-webhook</code>.
        </Typography>
      </Box>

      <Alert severity="info" data-testid="phase2-banner">
        <AlertTitle>Webhook editing available in Phase 2</AlertTitle>
        URL, signing-secret rotation and event-type changes will be enabled in Phase 2.
        Contact your GMEPay+ account manager to make changes now.
      </Alert>

      {status === 'loading' && <LoadingSkeleton variant="table" rows={3} />}
      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load webhooks.'} onRetry={retry} />
      )}

      {data && (
        <Card>
          <CardContent>
            {data.length === 0 ? (
              <EmptyState
                title="No webhooks configured"
                message="You haven't subscribed to any event types yet. Your account manager can add subscriptions for payment, settlement, and prefunding events."
              />
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>URL</TableCell>
                      <TableCell>Event types</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Last delivered at</TableCell>
                      <TableCell>Created</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.map((w) => (
                      <TableRow key={w.id}>
                        <TableCell sx={{ fontFamily: 'monospace' }}>{w.url}</TableCell>
                        <TableCell>
                          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                            {w.events.map((e) => (
                              <Chip key={e} label={e} size="small" variant="outlined" />
                            ))}
                          </Stack>
                        </TableCell>
                        <TableCell>
                          <Chip
                            label={w.active ? 'Active' : 'Disabled'}
                            color={w.active ? 'success' : 'default'}
                            size="small"
                          />
                        </TableCell>
                        <TableCell>
                          {w.lastDeliveryAt ? (
                            <Stack direction="row" spacing={1} alignItems="center">
                              <span>{new Date(w.lastDeliveryAt).toLocaleString()}</span>
                              {w.lastDeliveryStatus && (
                                <Chip
                                  label={w.lastDeliveryStatus}
                                  color={w.lastDeliveryStatus === 'OK' ? 'success' : 'error'}
                                  size="small"
                                />
                              )}
                            </Stack>
                          ) : (
                            '—'
                          )}
                        </TableCell>
                        <TableCell>{new Date(w.createdAt).toLocaleString()}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

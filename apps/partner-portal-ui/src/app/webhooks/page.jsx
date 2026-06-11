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
import { fetchWebhooks } from '@/store/webhooksSlice';
import { currentPartnerId } from '@/api/client';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';

/**
 * Webhooks page.
 *
 * Wire shape — GET /v1/portal/{partnerId}/webhooks returns
 *   Array<WebhookConfigView>
 *     { url, eventTypes, status, lastDeliveredAt }
 *
 * Notes:
 *   - There is NO `id` field on the wire — we key rows by `url` (which is
 *     unique within a partner's webhook set).
 *   - `eventTypes` (not `events`) is the array of subscribed event names.
 *   - `status` is a free-form string ("ACTIVE" in the Phase-1 stub) rendered
 *     as a chip; we treat "ACTIVE" as success-colored and anything else as
 *     default.
 *   - There is no createdAt or delivery-status field on the wire.
 */
function statusColor(status) {
  return String(status).toUpperCase() === 'ACTIVE' ? 'success' : 'default';
}

export default function WebhooksPage() {
  const dispatch = useDispatch();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s) => s.webhooks);

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

  const rows = data ?? [];

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

      {status === 'succeeded' && (
        <Card>
          <CardContent>
            {rows.length === 0 ? (
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
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((w) => (
                      <TableRow key={w.url}>
                        <TableCell sx={{ fontFamily: 'monospace' }}>{w.url}</TableCell>
                        <TableCell>
                          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                            {(w.eventTypes ?? []).map((e) => (
                              <Chip key={e} label={e} size="small" variant="outlined" />
                            ))}
                          </Stack>
                        </TableCell>
                        <TableCell>
                          <Chip
                            label={w.status ?? 'UNKNOWN'}
                            color={statusColor(w.status)}
                            size="small"
                          />
                        </TableCell>
                        <TableCell>
                          {w.lastDeliveredAt
                            ? new Date(w.lastDeliveredAt).toLocaleString()
                            : '—'}
                        </TableCell>
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

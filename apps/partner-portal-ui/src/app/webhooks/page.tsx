'use client';
import * as React from 'react';
import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
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
import type { WebhookConfigDto } from '@/api/types';

export default function WebhooksPage() {
  const partnerId = currentPartnerId();
  const [data, setData] = React.useState<WebhookConfigDto[] | null>(null);
  const [status, setStatus] = React.useState<'idle' | 'loading' | 'succeeded' | 'failed'>(
    'idle'
  );
  const [error, setError] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!partnerId) return;
    setStatus('loading');
    portalApi
      .listWebhooks(partnerId)
      .then((d) => {
        setData(d);
        setStatus('succeeded');
      })
      .catch((e: Error) => {
        setError(e.message);
        setStatus('failed');
      });
  }, [partnerId]);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Webhooks</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only listing of webhook subscriptions. Editing is reserved for Phase 2.
        </Typography>
      </Box>

      <Alert severity="info">
        Webhook configuration changes (URL, signing secret rotation, event types) will be enabled
        in Phase 2. Contact your GMEPay+ account manager to make changes now.
      </Alert>

      {status === 'loading' && <CircularProgress />}
      {status === 'failed' && <Alert severity="error">{error}</Alert>}

      {data && (
        <Card>
          <CardContent>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>URL</TableCell>
                    <TableCell>Events</TableCell>
                    <TableCell>State</TableCell>
                    <TableCell>Last delivery</TableCell>
                    <TableCell>Created</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={5} sx={{ textAlign: 'center', py: 4 }}>
                        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                          No webhooks configured.
                        </Typography>
                      </TableCell>
                    </TableRow>
                  )}
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
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

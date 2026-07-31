'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  MenuItem,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchWebhookEndpointHealth,
  rotateWebhookEndpointSecret,
  clearWebhookRotateResult,
} from '@/store/partnerLifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import WebhookSecretRevealModal from './WebhookSecretRevealModal';

/**
 * WebhookSecretPanel — webhook endpoint signing secrets for one partner (gap T5-8).
 *
 * WHY THIS PANEL EXISTS, in the words an operator needs:
 *
 * GMEPay+ used to sign every partner's webhooks with a single shared key. Each endpoint now has
 * its own secret, derived from the endpoint's identity, and a delivery is only signed when the
 * platform can prove the secret it derived is the exact one that partner was given. That is a
 * strictly better position — but it means **any endpoint registered before the change can no
 * longer be signed for at all**: its original secret was random, was revealed once, and was
 * never stored, so nothing can reproduce it. Those endpoints are not degraded, they are silent:
 * the partner receives nothing and there is no error on their side to notice.
 *
 * Rotating issues a fresh derivable secret and revives the endpoint. It is deliberately manual —
 * a rotated secret has to be handed to the partner out of band, so rotating on the platform's
 * own initiative would replace "receives nothing" with "receives events it cannot verify and was
 * never told why".
 *
 * Props:
 *   partnerCode: string
 */

/** Chip colour per signing status. Anything not SIGNABLE is a real outage, not a warning. */
function statusColor(status, deliverable) {
  if (deliverable) return 'success';
  return status === 'ROOT_KEY_MISSING' ? 'warning' : 'error';
}

/** Short label — the raw enum is precise but not readable in a table cell. */
function statusLabel(status) {
  switch (status) {
    case 'SIGNABLE':
      return 'Signing';
    case 'SECRET_NOT_DERIVABLE':
      return 'Cannot sign — rotate';
    case 'NO_SECRET_DIGEST':
      return 'No secret on file — rotate';
    case 'ROOT_KEY_MISSING':
      return 'Platform key missing';
    default:
      return status ?? '—';
  }
}

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString();
  } catch {
    return iso;
  }
}

/** Overlap presets, in minutes. 0 = immediate cutover (the retired secret dies at once). */
const OVERLAP_OPTIONS = [
  { value: 1440, label: '24 hours (default)' },
  { value: 4320, label: '3 days' },
  { value: 10080, label: '7 days' },
  { value: 0, label: 'Immediate — no overlap' },
];

export default function WebhookSecretPanel({ partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const {
    webhookEndpoints,
    webhookEndpointsLoading,
    webhookEndpointsError,
    rotatingEndpointId,
    webhookRotateResult,
  } = useAppSelector((s) => s.partnerLifecycle);

  // The row the confirm dialog is about; null when the dialog is closed.
  const [pending, setPending] = useState(null);
  const [reason, setReason] = useState('');
  const [overlapMinutes, setOverlapMinutes] = useState(1440);
  // Remembered across the reveal so the modal can show which endpoint was rotated.
  const [rotatedEndpoint, setRotatedEndpoint] = useState(null);

  const load = useCallback(() => {
    if (partnerCode) dispatch(fetchWebhookEndpointHealth(partnerCode));
  }, [dispatch, partnerCode]);

  useEffect(() => {
    load();
  }, [load]);

  const undeliverable = webhookEndpoints.filter((e) => !e.deliverable);
  const rotatable = undeliverable.filter((e) => e.fixableByRotation);

  const openConfirm = (endpoint) => {
    setPending(endpoint);
    setReason('');
    setOverlapMinutes(1440);
  };

  const closeConfirm = () => setPending(null);

  const confirmRotate = async () => {
    if (!pending) return;
    const endpoint = pending;
    setPending(null);
    try {
      await dispatch(
        rotateWebhookEndpointSecret({
          endpointId: endpoint.endpointId,
          reason: reason.trim() || undefined,
          overlapMinutes,
        }),
      ).unwrap();
      setRotatedEndpoint(endpoint);
      // WebhookSecretRevealModal opens off webhookRotateResult.
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      snackbar.error(`Rotation failed: ${msg}`);
    }
  };

  const handleRevealClose = () => {
    dispatch(clearWebhookRotateResult());
    setRotatedEndpoint(null);
    load(); // the row should now report SIGNABLE
  };

  if (webhookEndpointsLoading && webhookEndpoints.length === 0) {
    return <LoadingSkeleton variant="table" rows={2} />;
  }

  return (
    <Box data-testid="webhook-secret-panel">
      <Typography variant="h6" gutterBottom>
        Webhook signing secrets
      </Typography>

      <ErrorAlert message={webhookEndpointsError} onRetry={load} />

      {/*
        The explanation an operator needs BEFORE they see the table, and only when it is
        actually actionable — a partner whose endpoints are all fine should not be told to go
        rotate things.
      */}
      {rotatable.length > 0 && (
        <Alert severity="error" sx={{ mb: 2 }} data-testid="undeliverable-warning">
          <AlertTitle>
            {rotatable.length === 1
              ? 'This partner is receiving no webhooks'
              : `${rotatable.length} endpoints are receiving no webhooks`}
          </AlertTitle>
          Webhook signing moved from one shared platform key to a per-endpoint secret. Endpoints
          registered <strong>before that change cannot be signed for at all</strong> — their
          original secret was revealed once and never stored, so it can never be reproduced, and
          GMEPay+ now refuses to sign with anything else rather than send a signature the partner
          cannot verify. Deliveries for these endpoints stay queued.
          <Box component="p" sx={{ mb: 0, mt: 1 }}>
            <strong>Rotate to fix it, then send the new secret to the partner.</strong> The secret
            is shown once, here, and nowhere else — nothing in the platform can tell them for you.
          </Box>
        </Alert>
      )}

      {undeliverable.some((e) => e.status === 'ROOT_KEY_MISSING') && (
        <Alert severity="warning" sx={{ mb: 2 }} data-testid="root-key-warning">
          <AlertTitle>Platform signing key is not configured</AlertTitle>
          No webhook can be signed for <em>any</em> partner in this environment, and rotation is
          refused until it is set — the platform will not issue a secret it could not reproduce.
          This is a deployment fix (<code>GMEPAY_WEBHOOK_SIGNING_SECRET</code>), not a per-endpoint
          one.
        </Alert>
      )}

      {webhookEndpoints.length === 0 && !webhookEndpointsLoading && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
          No webhook endpoints registered for this partner.
        </Typography>
      )}

      {webhookEndpoints.length > 0 && (
        <Table size="small" aria-label="Webhook endpoints">
          <TableHead>
            <TableRow>
              <TableCell>Environment</TableCell>
              <TableCell>Endpoint URL</TableCell>
              <TableCell>Generation</TableCell>
              <TableCell>Registered</TableCell>
              <TableCell>Signing status</TableCell>
              <TableCell align="right">Action</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {webhookEndpoints.map((ep) => (
              <TableRow key={ep.endpointId} data-testid={`webhook-endpoint-row-${ep.endpointId}`}>
                <TableCell>{ep.environment ?? '—'}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.75rem', wordBreak: 'break-all' }}>
                  {ep.webhookUrl ?? '—'}
                </TableCell>
                <TableCell>{ep.secretGeneration ?? '—'}</TableCell>
                <TableCell>{formatDate(ep.createdAt)}</TableCell>
                <TableCell>
                  <Tooltip title={ep.detail ?? ''}>
                    <Chip
                      size="small"
                      label={statusLabel(ep.status)}
                      color={statusColor(ep.status, ep.deliverable)}
                      data-testid={`webhook-status-${ep.endpointId}`}
                    />
                  </Tooltip>
                </TableCell>
                <TableCell align="right">
                  <Button
                    size="small"
                    variant={ep.fixableByRotation ? 'contained' : 'outlined'}
                    color={ep.fixableByRotation ? 'error' : 'primary'}
                    startIcon={
                      rotatingEndpointId === ep.endpointId ? (
                        <CircularProgress size={14} />
                      ) : (
                        <RefreshIcon fontSize="small" />
                      )
                    }
                    disabled={
                      rotatingEndpointId !== null || ep.status === 'ROOT_KEY_MISSING'
                    }
                    onClick={() => openConfirm(ep)}
                    data-testid={`rotate-webhook-btn-${ep.endpointId}`}
                  >
                    Rotate secret
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {/* Confirm dialog — rotation invalidates a secret a live integration may be using. */}
      <Dialog
        open={pending !== null}
        onClose={closeConfirm}
        aria-labelledby="rotate-webhook-title"
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle id="rotate-webhook-title">Rotate webhook signing secret</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            {pending?.deliverable
              ? 'This endpoint is signing correctly today. Rotating replaces the secret the '
                + 'partner is using right now — they must be given the new one before the '
                + 'overlap window closes, or their verification will start failing.'
              : 'This endpoint cannot be signed for, so no events are reaching the partner. '
                + 'Rotating issues a secret the platform can reproduce and deliveries resume '
                + '— once the partner has the new value.'}
          </DialogContentText>

          <TextField
            select
            fullWidth
            label="Keep the previous secret valid for"
            value={overlapMinutes}
            onChange={(e) => setOverlapMinutes(Number(e.target.value))}
            helperText={
              overlapMinutes === 0
                ? 'The previous secret stops working immediately.'
                : 'During the overlap each delivery carries both signatures, so the partner can '
                  + 'redeploy on their own schedule without losing events.'
            }
            sx={{ mb: 2 }}
            inputProps={{ 'data-testid': 'overlap-select' }}
          >
            {OVERLAP_OPTIONS.map((o) => (
              <MenuItem key={o.value} value={o.value}>
                {o.label}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            fullWidth
            label="Reason (recorded in the operator audit trail)"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            multiline
            rows={2}
            inputProps={{ 'data-testid': 'rotate-reason-input' }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={closeConfirm}>Cancel</Button>
          <Button
            variant="contained"
            color="error"
            onClick={confirmRotate}
            data-testid="confirm-rotate-webhook-btn"
          >
            Rotate secret
          </Button>
        </DialogActions>
      </Dialog>

      {/* One-time reveal — the only place this secret ever exists in readable form. */}
      <WebhookSecretRevealModal
        open={webhookRotateResult !== null}
        rotation={webhookRotateResult}
        endpoint={rotatedEndpoint}
        onClose={handleRevealClose}
      />
    </Box>
  );
}

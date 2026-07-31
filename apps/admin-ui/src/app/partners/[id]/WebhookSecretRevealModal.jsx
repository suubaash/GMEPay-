'use client';

import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  InputAdornment,
  TextField,
  Typography,
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { useState } from 'react';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * WebhookSecretRevealModal — shows a newly rotated webhook signing secret exactly once.
 *
 * Follows OneTimeCredentialModal's pattern (read-only field + copy button + an explicit
 * "I've copied this" acknowledgement) and adds the two facts that are specific to a webhook
 * secret rotation and that the operator has to act on:
 *
 *  1. The partner MUST be told this value out of band. Nothing else in the platform can tell
 *     them — GMEPay+ stores only a digest, and the old secret stops working when the overlap
 *     window closes.
 *  2. The overlap deadline. Until it passes, every delivery carries BOTH the new and the old
 *     signature, so the partner can redeploy whenever they like without dropping events. After
 *     it, only the new one — which is when an un-communicated secret turns into an outage.
 *
 * The plaintext is never persisted or logged here; it lives only in the props of this dialog
 * and is dropped from the store when onClose fires.
 *
 * Props:
 *   open:     boolean
 *   rotation: { endpointId, signingSecretPlaintext, secretGeneration, previousSecretExpiresAt }
 *             | null
 *   endpoint: EndpointSigningHealth | null  — the row that was rotated, for context (optional)
 *   onClose:  () => void
 */
function formatDateTime(iso) {
  if (!iso) return null;
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

export default function WebhookSecretRevealModal({ open, rotation, endpoint, onClose }) {
  const snackbar = useSnackbar();
  const [copied, setCopied] = useState(false);

  const secret = rotation?.signingSecretPlaintext ?? '';
  const overlapUntil = formatDateTime(rotation?.previousSecretExpiresAt);

  const handleCopy = async () => {
    if (!secret) return;
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      snackbar.success('Signing secret copied to clipboard');
    } catch {
      snackbar.error('Could not copy — please select and copy manually');
    }
  };

  const handleClose = () => {
    setCopied(false);
    onClose?.();
  };

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      aria-labelledby="webhook-secret-reveal-title"
      maxWidth="sm"
      fullWidth
      data-testid="webhook-secret-reveal-modal"
    >
      <DialogTitle id="webhook-secret-reveal-title">New webhook signing secret</DialogTitle>
      <DialogContent>
        <Alert severity="warning" sx={{ mb: 2 }}>
          <AlertTitle>Shown once only — send it to the partner</AlertTitle>
          Copy this secret now: it cannot be retrieved after you close this dialog, because
          GMEPay+ stores only a one-way digest of it. <strong>The partner must receive it out
          of band</strong> — they need it to verify the <code>X-GME-Webhook-Signature</code>
          header on every event we send them.
        </Alert>

        {overlapUntil ? (
          <Alert severity="info" sx={{ mb: 2 }} data-testid="overlap-window-notice">
            Until <strong>{overlapUntil}</strong> every delivery is signed with both the new and
            the previous secret, so the partner can switch whenever they redeploy. After that
            only the new secret is sent.
          </Alert>
        ) : (
          <Alert severity="warning" sx={{ mb: 2 }} data-testid="immediate-cutover-notice">
            Immediate cutover — the previous secret is <strong>already invalid</strong>. Any
            partner-side verification using it fails from the next event onward.
          </Alert>
        )}

        <Box sx={{ mb: 2, display: 'flex', gap: 3, flexWrap: 'wrap' }}>
          <Box>
            <Typography variant="caption" color="text.secondary">
              Endpoint
            </Typography>
            <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
              {rotation?.endpointId ?? '—'}
            </Typography>
          </Box>
          <Box>
            <Typography variant="caption" color="text.secondary">
              Environment
            </Typography>
            <Typography variant="body2">{endpoint?.environment ?? '—'}</Typography>
          </Box>
          <Box>
            <Typography variant="caption" color="text.secondary">
              Generation
            </Typography>
            <Typography variant="body2">{rotation?.secretGeneration ?? '—'}</Typography>
          </Box>
        </Box>

        <TextField
          label="Signing secret"
          value={secret}
          fullWidth
          multiline
          rows={2}
          inputProps={{
            readOnly: true,
            'data-testid': 'webhook-secret-field',
            style: { fontFamily: 'monospace', fontSize: '0.8rem', wordBreak: 'break-all' },
          }}
          InputProps={{
            endAdornment: (
              <InputAdornment position="end">
                <IconButton
                  aria-label="copy signing secret"
                  onClick={handleCopy}
                  edge="end"
                  data-testid="copy-webhook-secret-btn"
                >
                  <ContentCopyIcon fontSize="small" />
                </IconButton>
              </InputAdornment>
            ),
          }}
        />
      </DialogContent>
      <DialogActions>
        <Button
          variant="contained"
          onClick={handleClose}
          data-testid="confirm-webhook-secret-copied-btn"
        >
          {copied ? "Done — I've copied this" : "I've copied this"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

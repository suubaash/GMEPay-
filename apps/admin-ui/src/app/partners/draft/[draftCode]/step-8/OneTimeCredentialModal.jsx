'use client';

import {
  Alert,
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
 * OneTimeCredentialModal — displays a newly issued or rotated credential
 * plaintext secret exactly once.
 *
 * The caller receives the OneTimeCredentialView from the backend which includes
 * `plaintextSecret`. This value is shown here and cannot be retrieved again.
 * The modal does NOT persist or log the secret.
 *
 * Props:
 *   open:       boolean
 *   credential: OneTimeCredentialView | null
 *     { id, env, kind, prefix, last4, issuedAt, expiresAt, plaintextSecret }
 *   onClose:    () => void   — called when user clicks "I've copied this"
 */
export default function OneTimeCredentialModal({ open, credential, onClose }) {
  const snackbar = useSnackbar();
  const [copied, setCopied] = useState(false);

  const secret = credential?.plaintextSecret ?? '';

  const handleCopy = async () => {
    if (!secret) return;
    try {
      await navigator.clipboard.writeText(secret);
      setCopied(true);
      snackbar.success('Secret copied to clipboard');
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
      aria-labelledby="one-time-cred-title"
      maxWidth="sm"
      fullWidth
      data-testid="one-time-credential-modal"
    >
      <DialogTitle id="one-time-cred-title">New credential issued</DialogTitle>
      <DialogContent>
        <Alert severity="warning" sx={{ mb: 2 }}>
          This secret is shown <strong>once only</strong>. Copy it now — it
          cannot be retrieved after you close this dialog.
        </Alert>

        {credential && (
          <Box sx={{ mb: 2, display: 'flex', gap: 3, flexWrap: 'wrap' }}>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Environment
              </Typography>
              <Typography variant="body2">{credential.env ?? '—'}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Kind
              </Typography>
              <Typography variant="body2">{credential.kind ?? '—'}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Prefix
              </Typography>
              <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                {credential.prefix ?? '—'}
              </Typography>
            </Box>
          </Box>
        )}

        <TextField
          label="Secret"
          value={secret}
          fullWidth
          multiline
          rows={3}
          inputProps={{
            readOnly: true,
            'data-testid': 'secret-field',
            style: { fontFamily: 'monospace', fontSize: '0.8rem', wordBreak: 'break-all' },
          }}
          InputProps={{
            endAdornment: (
              <InputAdornment position="end">
                <IconButton
                  aria-label="copy secret"
                  onClick={handleCopy}
                  edge="end"
                  data-testid="copy-secret-btn"
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
          data-testid="confirm-copied-btn"
        >
          {copied ? "Done — I've copied this" : "I've copied this"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

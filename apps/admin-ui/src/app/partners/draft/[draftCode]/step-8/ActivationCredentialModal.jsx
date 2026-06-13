'use client';

import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { useAppDispatch } from '@/store';
import { dismissBundle } from '@/store/lifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * A single secret row: masked-by-default text field with toggle-reveal and
 * copy-to-clipboard button.
 *
 * @param {object}  props
 * @param {string}  props.label    Display label.
 * @param {string}  props.value    The secret value.
 * @param {string}  props.inputId  Unique id for the input (for aria-label).
 */
function SecretField({ label, value, inputId }) {
  const [revealed, setRevealed] = useState(false);
  const snackbar = useSnackbar();

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value ?? '');
      snackbar.success(`${label} copied to clipboard.`);
    } catch {
      snackbar.error('Copy failed — please select and copy manually.');
    }
  };

  return (
    <TextField
      id={inputId}
      label={label}
      value={value ?? ''}
      type={revealed ? 'text' : 'password'}
      fullWidth
      size="small"
      inputProps={{
        readOnly: true,
        'aria-label': inputId,
      }}
      InputProps={{
        endAdornment: (
          <InputAdornment position="end">
            <IconButton
              aria-label={`toggle-${inputId}`}
              onClick={() => setRevealed((v) => !v)}
              edge="end"
              size="small"
            >
              {revealed ? (
                <VisibilityOffIcon fontSize="small" />
              ) : (
                <VisibilityIcon fontSize="small" />
              )}
            </IconButton>
            <IconButton
              aria-label={`copy-${inputId}`}
              onClick={handleCopy}
              edge="end"
              size="small"
            >
              <ContentCopyIcon fontSize="small" />
            </IconButton>
          </InputAdornment>
        ),
      }}
    />
  );
}

/**
 * ActivationCredentialModal — displayed exactly once after a successful partner
 * activation. Shows the plaintext API key, HMAC secret, and webhook signing
 * secret with mask/reveal and copy-to-clipboard.
 *
 * Security contract:
 *   - Values are shown only here, one time.
 *   - The "Done" button is disabled until the operator checks the confirmation
 *     box ("I have stored these values securely").
 *   - On dismiss, `dismissBundle()` is dispatched, wiping the credentials
 *     from Redux state. The modal cannot be reopened after this.
 *
 * @param {object|null}  props.bundle  IssuedCredentialBundle from Redux lifecycle state.
 */
export function ActivationCredentialModal({ bundle }) {
  const dispatch = useAppDispatch();
  const [confirmed, setConfirmed] = useState(false);

  const open = bundle !== null && bundle !== undefined;

  const handleDone = () => {
    if (!confirmed) return;
    // Wipe credentials from Redux — they cannot be recovered after this.
    dispatch(dismissBundle());
  };

  if (!open) return null;

  return (
    <Dialog
      open
      maxWidth="sm"
      fullWidth
      disableEscapeKeyDown
      aria-label="one-time-credential-modal"
      aria-describedby="credential-modal-warning"
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <WarningAmberIcon color="warning" />
        Partner credentials — save these now
      </DialogTitle>

      <DialogContent>
        <Alert
          id="credential-modal-warning"
          severity="warning"
          sx={{ mb: 3 }}
          aria-label="one-time-warning"
        >
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            This is the only time you will see these values. Store them in your
            secret manager NOW. They cannot be recovered after this dialog is
            closed.
          </Typography>
        </Alert>

        <Stack spacing={2}>
          {/* Non-secret display: key ID, prefix, last 4 */}
          <Box>
            <Typography variant="caption" color="text.secondary">
              Key ID
            </Typography>
            <Typography
              variant="body2"
              fontFamily="monospace"
              aria-label="key-id-display"
            >
              {bundle?.keyId ?? '—'}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', gap: 4 }}>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Prefix
              </Typography>
              <Typography variant="body2" fontFamily="monospace" aria-label="key-prefix-display">
                {bundle?.keyPrefix ?? '—'}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">
                Last 4
              </Typography>
              <Typography variant="body2" fontFamily="monospace" aria-label="key-last4-display">
                {bundle?.keyLast4 ?? '—'}
              </Typography>
            </Box>
          </Box>

          <Divider />

          {/* Secrets — masked by default */}
          <SecretField
            label="API key"
            value={bundle?.plaintextApiKey}
            inputId="plaintext-api-key"
          />
          <SecretField
            label="HMAC secret"
            value={bundle?.plaintextHmac}
            inputId="plaintext-hmac"
          />
          <SecretField
            label="Webhook signing secret"
            value={bundle?.plaintextWebhookSecret}
            inputId="plaintext-webhook-secret"
          />
        </Stack>

        <FormControlLabel
          sx={{ mt: 3 }}
          control={
            <Checkbox
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
              inputProps={{ 'aria-label': 'confirm-stored-checkbox' }}
            />
          }
          label="I have stored these values securely in my secret manager"
        />
      </DialogContent>

      <DialogActions>
        <Button
          variant="contained"
          color="primary"
          disabled={!confirmed}
          onClick={handleDone}
          aria-label="done-dismiss-credentials"
        >
          Done
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export default ActivationCredentialModal;

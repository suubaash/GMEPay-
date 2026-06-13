'use client';

import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { proposeActivate, executeActivate } from '@/store/lifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * ActivateButton — 4-eyes activation control for the partner setup wizard.
 *
 * Flow:
 *   1. First operator click  → POST .../lifecycle/activate → 202 PROPOSED.
 *      Shows "Awaiting second-operator approval" banner.
 *   2. Second operator click (after role-switch / re-login in dev) → same
 *      endpoint → 201 ACTIVATED + IssuedCredentialBundle.
 *      Calls onActivated(bundle) which triggers OneTimeCredentialModal.
 *
 * Disabled when any precondition is unmet (`allMet` prop = false).
 *
 * @param {object}    props
 * @param {string}    props.partnerCode   Partner being activated.
 * @param {boolean}   props.allMet        True when all preconditions pass.
 * @param {Function}  props.onActivated   Called with IssuedCredentialBundle on success.
 */
export function ActivateButton({ partnerCode, allMet, onActivated }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const saving = useAppSelector((s) => s.lifecycle?.saving ?? false);
  const activationByCode = useAppSelector((s) => s.lifecycle?.activationByCode ?? {});
  const activation = activationByCode[partnerCode] ?? null;

  const isProposed = activation?.status === 'PROPOSED';
  const isActivated = activation?.status === 'ACTIVATED';

  const [localBusy, setLocalBusy] = useState(false);
  const busy = saving || localBusy;

  const handleClick = async () => {
    if (!partnerCode) return;
    setLocalBusy(true);
    try {
      if (!isProposed) {
        // First click: propose activation
        await dispatch(proposeActivate(partnerCode)).unwrap();
        snackbar.info('Activation proposed — a second operator must confirm.');
      } else {
        // Second click: execute activation
        const result = await dispatch(executeActivate(partnerCode)).unwrap();
        snackbar.success('Partner activated successfully.');
        if (typeof onActivated === 'function' && result?.result) {
          onActivated(result.result);
        }
      }
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'Activation failed');
      snackbar.error(message);
    } finally {
      setLocalBusy(false);
    }
  };

  if (isActivated) {
    return (
      <Alert severity="success" aria-label="activation-success-banner">
        Partner has been activated successfully.
      </Alert>
    );
  }

  return (
    <Box>
      {isProposed && (
        <Alert severity="warning" sx={{ mb: 2 }} aria-label="activation-proposed-banner">
          <Typography variant="body2">
            <strong>Activation proposed.</strong> A second operator must log in
            and click &quot;Confirm activation&quot; to complete the 4-eyes process.
          </Typography>
        </Alert>
      )}

      {!allMet && (
        <Alert severity="error" sx={{ mb: 2 }} aria-label="preconditions-unmet-banner">
          One or more activation preconditions are not met. Review the checklist
          above and resolve them before activating.
        </Alert>
      )}

      <Button
        variant="contained"
        color="success"
        size="large"
        disabled={!allMet || busy}
        onClick={handleClick}
        startIcon={busy ? <CircularProgress size={16} color="inherit" /> : null}
        aria-label={isProposed ? 'confirm-activation' : 'propose-activation'}
      >
        {busy
          ? 'Processing…'
          : isProposed
          ? 'Confirm activation'
          : 'Activate partner'}
      </Button>
    </Box>
  );
}

export default ActivateButton;

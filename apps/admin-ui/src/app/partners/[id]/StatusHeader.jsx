'use client';

import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormHelperText,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import LinkIcon from '@mui/icons-material/Link';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  proposeLifecycleTransition,
  executeLifecycleTransition,
  clearLifecycleError,
} from '@/store/partnerLifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import { STEPS } from '../draft/[draftCode]/page';

/**
 * Six-value suspension-reason enum (matches backend SuspendReason).
 */
const SUSPEND_REASONS = [
  'COMPLIANCE_REVIEW',
  'FRAUD_SUSPECTED',
  'CONTRACT_BREACH',
  'REGULATORY_DIRECTIVE',
  'OPERATOR_REQUEST',
  'OTHER',
];

/**
 * Color map for partner lifecycle statuses.
 */
function statusColor(status) {
  switch (status) {
    case 'LIVE':
      return 'success';
    case 'SUSPENDED':
      return 'warning';
    case 'TERMINATED':
      return 'error';
    case 'ONBOARDING':
    case 'ONBOARDING_PROPOSED':
      return 'info';
    default:
      return 'default';
  }
}

/**
 * Derive wizard step index from partner status for "Resume wizard" link.
 * Returns null for post-activation statuses.
 */
function wizardStepFor(status) {
  if (!status) return null;
  if (status === 'ONBOARDING') return 1;
  if (['LIVE', 'SUSPENDED', 'TERMINATED'].includes(status)) return null;
  // Other ONBOARDING sub-statuses — map back to last wizard step
  return 1;
}

/**
 * StatusHeader — displays the partner's lifecycle status chip + FSM
 * transitions menu.
 *
 * Transition menu depends on current status:
 *   LIVE:       Suspend, Terminate
 *   SUSPENDED:  Reactivate, Terminate
 *   TERMINATED: read-only, shows terminated_at + reason
 *   Others:     "Resume wizard" link to step-N
 *
 * 4-eyes flow:
 *   First operator click → proposeLifecycleTransition → shows
 *     "Awaiting approval" badge with changeRequestId.
 *   Second operator click → executeLifecycleTransition.
 *
 * Props:
 *   partnerCode:  string
 *   status:       string
 *   terminatedAt: string|null   ISO instant
 *   terminationReason: string|null
 *   onTransitionDone: () => void  — called after execute succeeds (reload parent)
 */
export default function StatusHeader({
  partnerCode,
  status,
  terminatedAt,
  terminationReason,
  onTransitionDone,
}) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const lifecycleState = useAppSelector((s) => s.partnerLifecycle.lifecycle[partnerCode]);

  const proposing = lifecycleState?.proposing ?? false;
  const executing = lifecycleState?.executing ?? false;
  const pendingId = lifecycleState?.pendingChangeRequestId ?? null;
  const lcError = lifecycleState?.error ?? null;

  // Dialog state
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogAction, setDialogAction] = useState(null); // 'SUSPEND'|'REACTIVATE'|'TERMINATE'
  const [suspendReason, setSuspendReason] = useState('');
  const [terminateReason, setTerminateReason] = useState('');

  const openDialog = (action) => {
    setDialogAction(action);
    setSuspendReason('');
    setTerminateReason('');
    dispatch(clearLifecycleError(partnerCode));
    setDialogOpen(true);
  };

  const handleDialogClose = () => {
    setDialogOpen(false);
    setDialogAction(null);
  };

  const validateAndPropose = async () => {
    const reason =
      dialogAction === 'SUSPEND' ? suspendReason : terminateReason;
    if (!reason || reason.trim() === '') {
      return; // guarded by button disable logic
    }

    try {
      await dispatch(
        proposeLifecycleTransition({ partnerCode, action: dialogAction, reason: reason.trim() }),
      ).unwrap();
      snackbar.info('Transition proposed — a second operator must approve.');
      setDialogOpen(false);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      snackbar.error(`Proposal failed: ${msg}`);
    }
  };

  const handleExecute = async () => {
    if (!pendingId) return;
    try {
      await dispatch(
        executeLifecycleTransition({ partnerCode, changeRequestId: pendingId }),
      ).unwrap();
      snackbar.success('Transition executed — partner status updated.');
      onTransitionDone?.();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      snackbar.error(`Execution failed: ${msg}`);
    }
  };

  // Derived flags
  const isLive = status === 'LIVE';
  const isSuspended = status === 'SUSPENDED';
  const isTerminated = status === 'TERMINATED';
  const wizardStep = wizardStepFor(status);
  const stepLabel = wizardStep != null ? STEPS[wizardStep - 1]?.label : null;

  const suspendReasonValid = suspendReason.trim() !== '';
  const terminateReasonValid = terminateReason.trim().length >= 5;

  return (
    <Box data-testid="status-header">
      <Stack direction="row" alignItems="center" spacing={2} flexWrap="wrap">
        {/* Status chip */}
        <Chip
          label={status ?? '—'}
          color={statusColor(status)}
          size="medium"
          data-testid="partner-status-chip"
        />

        {/* Terminated detail */}
        {isTerminated && (
          <Box>
            {terminatedAt && (
              <Typography variant="body2" color="text.secondary">
                Terminated at: {new Date(terminatedAt).toLocaleString()}
              </Typography>
            )}
            {terminationReason && (
              <Typography variant="body2" color="text.secondary">
                Reason: {terminationReason}
              </Typography>
            )}
          </Box>
        )}

        {/* 4-eyes pending notice */}
        {pendingId && (
          <Chip
            label={`Awaiting approval — CR #${pendingId}`}
            color="warning"
            variant="outlined"
            size="small"
            data-testid="pending-change-request-chip"
          />
        )}

        {/* Resume wizard link for pre-LIVE statuses */}
        {wizardStep != null && (
          <Button
            href={`/partners/draft/${partnerCode}/step-${wizardStep}`}
            startIcon={<LinkIcon />}
            size="small"
            variant="outlined"
            data-testid="resume-wizard-btn"
          >
            Resume wizard (Step {wizardStep}: {stepLabel})
          </Button>
        )}

        {/* LIVE transitions */}
        {isLive && !pendingId && (
          <Stack direction="row" spacing={1}>
            <Button
              variant="outlined"
              color="warning"
              endIcon={<ArrowDropDownIcon />}
              onClick={() => openDialog('SUSPEND')}
              data-testid="suspend-btn"
              disabled={proposing || executing}
            >
              Suspend
            </Button>
            <Button
              variant="outlined"
              color="error"
              endIcon={<ArrowDropDownIcon />}
              onClick={() => openDialog('TERMINATE')}
              data-testid="terminate-btn"
              disabled={proposing || executing}
            >
              Terminate
            </Button>
          </Stack>
        )}

        {/* SUSPENDED transitions */}
        {isSuspended && !pendingId && (
          <Stack direction="row" spacing={1}>
            <Button
              variant="outlined"
              color="success"
              onClick={() => openDialog('REACTIVATE')}
              data-testid="reactivate-btn"
              disabled={proposing || executing}
            >
              Reactivate
            </Button>
            <Button
              variant="outlined"
              color="error"
              onClick={() => openDialog('TERMINATE')}
              data-testid="terminate-btn"
              disabled={proposing || executing}
            >
              Terminate
            </Button>
          </Stack>
        )}

        {/* Execute pending transition (second operator) */}
        {pendingId && !isTerminated && (
          <Button
            variant="contained"
            color="primary"
            onClick={handleExecute}
            disabled={executing}
            data-testid="execute-transition-btn"
          >
            {executing ? 'Executing…' : 'Approve & Execute Transition'}
          </Button>
        )}
      </Stack>

      {/* Inline error */}
      {lcError && (
        <Alert severity="error" sx={{ mt: 1 }} data-testid="lifecycle-error">
          {lcError}
        </Alert>
      )}

      {/* Transition dialog */}
      <Dialog
        open={dialogOpen}
        onClose={handleDialogClose}
        aria-labelledby="transition-dialog-title"
        maxWidth="sm"
        fullWidth
        data-testid="transition-dialog"
      >
        <DialogTitle id="transition-dialog-title">
          {dialogAction === 'SUSPEND' && 'Suspend partner'}
          {dialogAction === 'REACTIVATE' && 'Reactivate partner'}
          {dialogAction === 'TERMINATE' && 'Terminate partner'}
        </DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }} data-testid="four-eyes-notice">
            This action requires approval by a second operator.
          </Alert>

          {dialogAction === 'SUSPEND' && (
            <FormControl fullWidth required error={!suspendReasonValid && suspendReason !== ''}>
              <InputLabel id="suspend-reason-label">Suspension reason</InputLabel>
              <Select
                labelId="suspend-reason-label"
                value={suspendReason}
                label="Suspension reason"
                onChange={(e) => setSuspendReason(e.target.value)}
                SelectDisplayProps={{ 'data-testid': 'suspend-reason-select' }}
                inputProps={{ 'aria-label': 'suspend-reason-select' }}
              >
                {SUSPEND_REASONS.map((r) => (
                  <MenuItem key={r} value={r}>
                    {r.replace(/_/g, ' ')}
                  </MenuItem>
                ))}
              </Select>
              {!suspendReasonValid && suspendReason !== '' && (
                <FormHelperText>Reason is required</FormHelperText>
              )}
            </FormControl>
          )}

          {dialogAction === 'REACTIVATE' && (
            <Typography variant="body2" color="text.secondary">
              Reactivating this partner will restore them to LIVE status, subject
              to second-operator approval.
            </Typography>
          )}

          {dialogAction === 'TERMINATE' && (
            <TextField
              label="Termination reason"
              required
              fullWidth
              multiline
              rows={3}
              value={terminateReason}
              onChange={(e) => setTerminateReason(e.target.value)}
              helperText={
                terminateReason.trim().length < 5
                  ? 'Please provide at least 5 characters'
                  : ''
              }
              error={terminateReason.trim().length > 0 && terminateReason.trim().length < 5}
              inputProps={{ 'data-testid': 'terminate-reason-input' }}
            />
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={handleDialogClose}>Cancel</Button>
          <Button
            variant="contained"
            color={dialogAction === 'TERMINATE' ? 'error' : 'primary'}
            onClick={validateAndPropose}
            disabled={
              proposing ||
              (dialogAction === 'SUSPEND' && !suspendReasonValid) ||
              (dialogAction === 'TERMINATE' && !terminateReasonValid)
            }
            data-testid="confirm-transition-btn"
          >
            {proposing ? 'Proposing…' : 'Propose'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

'use client';

import { useState } from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import FormControlLabel from '@mui/material/FormControlLabel';
import Checkbox from '@mui/material/Checkbox';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';

/**
 * The exact assertion the attester must send, byte-for-byte. The backend requires this string
 * verbatim (config-registry `ManualAttestationCommand.REQUIRED_ASSERTION`), which is why it lives
 * here as a constant rather than being composed from the form: the operator agrees to a specific
 * sentence, and the sentence they agree to must be the sentence that reaches the audit log.
 */
export const MANUAL_ATTESTATION_ASSERTION =
  'I performed this sanctions and PEP screening myself, following the SOP named above, '
  + 'and I am accountable for the result.';

const OUTCOMES = [
  { value: 'CLEAR', label: 'CLEAR — no matches found' },
  { value: 'NEEDS_REVIEW', label: 'NEEDS REVIEW — partial / uncertain matches' },
  { value: 'HIT', label: 'HIT — a confident match was found' },
];

/**
 * Record a MANUAL sanctions/PEP screening attestation (GAP T1-4, owner decision 2026-07-28).
 *
 * <h3>What this dialog is</h3>
 *
 * It is NOT a way to mark a partner as screened. It is the operator putting their name to a
 * statement that they personally carried out the screening described, under a compliance-signed
 * SOP. That is why:
 *
 *  - the attester is not a field — the server takes it from the verified token, so the operator
 *    cannot attest on someone else's behalf, and the copy says whose name will be recorded;
 *  - the SOP document reference and version are mandatory (an attestation with no procedure is
 *    an unfalsifiable "I checked", and activation refuses it);
 *  - "what was checked" is free text the attester writes, not a checklist of list names this app
 *    would be inventing on their behalf;
 *  - the confirmation is an explicit tick against the full sentence, shown in full, rather than a
 *    "Confirm" button on a summary.
 *
 * @param {object} props
 * @param {boolean} props.open
 * @param {string} props.partnerCode      partner the attestation is for (shown, never editable).
 * @param {boolean} [props.busy]          submission in flight.
 * @param {(payload:object)=>void} props.onSubmit  called with the wire body.
 * @param {()=>void} props.onCancel
 */
export default function ManualAttestationDialog({
  open,
  partnerCode,
  busy = false,
  onSubmit,
  onCancel,
}) {
  const [outcome, setOutcome] = useState('CLEAR');
  const [sopDocumentRef, setSopDocumentRef] = useState('');
  const [sopVersion, setSopVersion] = useState('');
  const [sourcesConsulted, setSourcesConsulted] = useState('');
  const [confirmed, setConfirmed] = useState(false);

  const sopRefMissing = sopDocumentRef.trim() === '';
  const sopVersionMissing = sopVersion.trim() === '';
  const sourcesMissing = sourcesConsulted.trim() === '';
  const canSubmit =
    !busy && !sopRefMissing && !sopVersionMissing && !sourcesMissing && confirmed;

  const submit = () => {
    if (!canSubmit) return;
    onSubmit({
      outcome,
      sopDocumentRef: sopDocumentRef.trim(),
      sopVersion: sopVersion.trim(),
      sourcesConsulted: sourcesConsulted.trim(),
      attestation: MANUAL_ATTESTATION_ASSERTION,
    });
  };

  return (
    <Dialog
      open={open}
      onClose={busy ? undefined : onCancel}
      fullWidth
      maxWidth="sm"
      aria-label="manual-attestation-dialog"
    >
      <DialogTitle>Record a manual screening attestation</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="warning" variant="outlined" aria-label="manual-attestation-warning">
            <AlertTitle>You are certifying that you performed this screening</AlertTitle>
            This does not run a screening. It records that <strong>you</strong> carried out the
            sanctions and PEP screening for <strong>{partnerCode}</strong> by hand, following the
            compliance-signed SOP you name below, and that you are accountable for the result.
            It is recorded against your verified operator identity on the tamper-evident audit
            trail and cannot be edited afterwards — a correction is a new attestation.
          </Alert>

          <Typography variant="body2" color="text.secondary">
            An attested manual screening satisfies the activation sanctions pre-condition. It stays
            visibly distinct from a vendor screening everywhere in the platform (it is recorded as
            &quot;Clear — manual SOP attestation&quot;, never as a plain &quot;Clear&quot;), because
            it consults no automated list feed and nothing rescreens the partner as lists change.
          </Typography>

          <TextField
            select
            label="What did the screening find?"
            value={outcome}
            onChange={(e) => setOutcome(e.target.value)}
            fullWidth
            disabled={busy}
            inputProps={{ 'aria-label': 'attestation-outcome' }}
            helperText="Record what you actually found. A HIT or NEEDS REVIEW is recorded as such
              and does not activate the partner on its own."
          >
            {OUTCOMES.map((o) => (
              <MenuItem key={o.value} value={o.value}>
                {o.label}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            label="SOP document"
            value={sopDocumentRef}
            onChange={(e) => setSopDocumentRef(e.target.value)}
            fullWidth
            required
            disabled={busy}
            error={sopRefMissing && confirmed}
            inputProps={{ maxLength: 128, 'aria-label': 'attestation-sop-document-ref' }}
            helperText="The compliance-signed procedure you followed, as compliance references it
              (document id, title or URI). Required — an attestation with no procedure behind it is
              refused at activation."
          />

          <TextField
            label="SOP version"
            value={sopVersion}
            onChange={(e) => setSopVersion(e.target.value)}
            fullWidth
            required
            disabled={busy}
            error={sopVersionMissing && confirmed}
            inputProps={{ maxLength: 32, 'aria-label': 'attestation-sop-version' }}
            helperText="The revision you followed (e.g. v3). Procedures change; a reviewer must be
              able to tell which one this attestation rests on."
          />

          <TextField
            label="Lists / sources you consulted"
            value={sourcesConsulted}
            onChange={(e) => setSourcesConsulted(e.target.value)}
            fullWidth
            required
            multiline
            minRows={3}
            disabled={busy}
            error={sourcesMissing && confirmed}
            inputProps={{ maxLength: 2000, 'aria-label': 'attestation-sources-consulted' }}
            helperText="In your own words: which lists, registers or sources you actually searched,
              and what you searched them for. This app deliberately does not offer a list of list
              names — naming a source you did not consult would be worse than naming none."
          />

          <FormControlLabel
            control={
              <Checkbox
                checked={confirmed}
                onChange={(e) => setConfirmed(e.target.checked)}
                disabled={busy}
                inputProps={{ 'aria-label': 'attestation-confirm' }}
              />
            }
            label={
              <Typography variant="body2">
                {MANUAL_ATTESTATION_ASSERTION}
              </Typography>
            }
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel} disabled={busy} aria-label="attestation-cancel">
          Cancel
        </Button>
        <Button
          onClick={submit}
          variant="contained"
          color="warning"
          disabled={!canSubmit}
          startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
          aria-label="attestation-submit"
        >
          Record attestation
        </Button>
      </DialogActions>
    </Dialog>
  );
}

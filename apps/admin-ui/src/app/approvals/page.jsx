'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import { useAppDispatch, useAppSelector } from '@/store';
import { approve, fetchPending, reject } from '@/store/approvalsSlice';
import { getUsername } from '@/api/auth';
import { isDevLoginAllowed } from '@/api/oidc';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Resolve the current operator's username for 4-eyes enforcement.
 *
 * Reads from the OIDC session via {@link getUsername} (which reads
 * localStorage: USER_KEY -> id_token claims). Falls back to the literal
 * string 'dev-operator' when NEXT_PUBLIC_ALLOW_DEV_LOGIN=true so local
 * dev iteration works without a live Keycloak session.
 */
function currentOperator() {
  const name = getUsername();
  if (name) return name;
  if (isDevLoginAllowed()) return 'dev-operator';
  return 'unknown';
}

/**
 * Format an ISO-8601 instant for the table "Proposed at" column.
 * Falls back gracefully when the value is missing or unparseable.
 */
function formatInstant(iso) {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(iso));
  } catch {
    return String(iso);
  }
}

/**
 * Summarise a change-request payload as a short human-readable string.
 * The payload is an arbitrary object — we show the top-level keys and
 * their (truncated) values, which is enough for an operator to recognise
 * what the request is about at a glance.
 */
function summarisePayload(payload) {
  if (!payload || typeof payload !== 'object') return '—';
  const entries = Object.entries(payload);
  if (entries.length === 0) return '(empty)';
  return entries
    .slice(0, 4)
    .map(([k, v]) => {
      const val = v === null || v === undefined ? 'null' : String(v);
      return `${k}: ${val.length > 40 ? val.slice(0, 40) + '…' : val}`;
    })
    .join(', ');
}

/**
 * Approvals queue — lists all PROPOSED change requests and lets an
 * operator approve or reject each one.
 *
 * 4-eyes rule: the Approve button is disabled when the row's `proposer`
 * matches the current operator (same check enforced server-side by
 * agent 2B.1).
 */
export default function ApprovalsPage() {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { items, loading, error, acting, actError } = useAppSelector(
    (s) => s.approvals,
  );
  const operator = currentOperator();

  // ---------- Reject dialog state ----------
  const [rejectDialog, setRejectDialog] = useState(null); // { id, aggregate } | null
  const [rejectReason, setRejectReason] = useState('');
  const [rejectReasonTouched, setRejectReasonTouched] = useState(false);

  // ---------- Load on mount ----------
  const reload = useCallback(() => {
    dispatch(fetchPending());
  }, [dispatch]);

  useEffect(() => {
    reload();
  }, [reload]);

  // ---------- Approve ----------
  const handleApprove = async (id) => {
    try {
      await dispatch(approve({ id, approvedBy: operator })).unwrap();
      snackbar.success('Change request approved.');
    } catch {
      snackbar.error(actError[id] ?? 'Approval failed');
    }
  };

  // ---------- Reject flow ----------
  const openRejectDialog = (cr) => {
    setRejectDialog({ id: cr.id, aggregate: cr.aggregate });
    setRejectReason('');
    setRejectReasonTouched(false);
  };

  const closeRejectDialog = () => {
    setRejectDialog(null);
    setRejectReason('');
    setRejectReasonTouched(false);
  };

  const handleRejectSubmit = async () => {
    setRejectReasonTouched(true);
    if (!rejectReason.trim()) return;
    const { id } = rejectDialog;
    closeRejectDialog();
    try {
      await dispatch(
        reject({ id, rejectedBy: operator, reason: rejectReason.trim() }),
      ).unwrap();
      snackbar.success('Change request rejected.');
    } catch {
      snackbar.error(actError[id] ?? 'Rejection failed');
    }
  };

  // ---------- Render ----------
  const rows = Array.isArray(items) ? items : [];
  const reasonMissing = rejectReasonTouched && !rejectReason.trim();

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Approvals
        </Typography>
        <Button variant="outlined" onClick={reload} disabled={loading}>
          Refresh
        </Button>
      </Box>

      <ErrorAlert
        message={error}
        onRetry={reload}
        title="Could not load pending approvals"
      />

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={4} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No pending approvals"
            description="All change requests have been actioned or none have been proposed yet."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Pending change requests">
            <TableHead>
              <TableRow>
                <TableCell>Aggregate</TableCell>
                <TableCell>Proposer</TableCell>
                <TableCell>Proposed at</TableCell>
                <TableCell>Changes</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((cr) => {
                const isSelf = cr.proposer === operator;
                const isActing = Boolean(acting[cr.id]);
                return (
                  <TableRow key={cr.id} hover>
                    <TableCell>{cr.aggregate ?? '—'}</TableCell>
                    <TableCell>{cr.proposer ?? '—'}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {formatInstant(cr.proposedAt)}
                    </TableCell>
                    <TableCell
                      sx={{
                        maxWidth: 360,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                      title={
                        cr.payload ? JSON.stringify(cr.payload, null, 2) : undefined
                      }
                    >
                      {summarisePayload(cr.payload)}
                    </TableCell>
                    <TableCell align="right">
                      <Box
                        sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}
                      >
                        <Tooltip
                          title={
                            isSelf
                              ? '4-eyes: a different operator must approve'
                              : 'Approve this change request'
                          }
                        >
                          {/* Span wrapper is required: MUI Tooltip needs a
                              focusable/hoverable child; disabled buttons swallow
                              mouse events without it. */}
                          <span>
                            <Button
                              size="small"
                              variant="contained"
                              color="success"
                              startIcon={<CheckCircleOutlineIcon />}
                              disabled={isSelf || isActing}
                              aria-label={`Approve change request ${cr.id}`}
                              onClick={() => handleApprove(cr.id)}
                            >
                              Approve
                            </Button>
                          </span>
                        </Tooltip>

                        <Tooltip title="Reject this change request">
                          <span>
                            <Button
                              size="small"
                              variant="outlined"
                              color="error"
                              startIcon={<CancelOutlinedIcon />}
                              disabled={isActing}
                              aria-label={`Reject change request ${cr.id}`}
                              onClick={() => openRejectDialog(cr)}
                            >
                              Reject
                            </Button>
                          </span>
                        </Tooltip>
                      </Box>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/* ---------- Reject reason dialog ---------- */}
      <Dialog
        open={Boolean(rejectDialog)}
        onClose={closeRejectDialog}
        maxWidth="sm"
        fullWidth
        aria-labelledby="reject-dialog-title"
      >
        <DialogTitle id="reject-dialog-title">
          Reject change request
          {rejectDialog?.aggregate ? ` — ${rejectDialog.aggregate}` : ''}
        </DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            multiline
            minRows={3}
            label="Reason (required)"
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            onBlur={() => setRejectReasonTouched(true)}
            error={reasonMissing}
            helperText={reasonMissing ? 'A reason is required before rejecting.' : ''}
            sx={{ mt: 1 }}
            inputProps={{ 'aria-label': 'rejection reason' }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={closeRejectDialog}>Cancel</Button>
          <Button
            variant="contained"
            color="error"
            onClick={handleRejectSubmit}
            aria-label="confirm reject"
          >
            Confirm reject
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

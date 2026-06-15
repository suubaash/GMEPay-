'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
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
import ReplayIcon from '@mui/icons-material/Replay';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  listExceptions,
  resolveException,
  reRunException,
} from '@/store/reconExceptionsSlice';
import { getUsername } from '@/api/auth';
import { isDevLoginAllowed } from '@/api/oidc';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Resolve the current operator's username for audit logging.
 * Falls back to 'dev-operator' when NEXT_PUBLIC_ALLOW_DEV_LOGIN=true.
 */
function currentOperator() {
  const name = getUsername();
  if (name) return name;
  if (isDevLoginAllowed()) return 'dev-operator';
  return 'unknown';
}

/**
 * Format an ISO-8601 instant (KST display per BS-04 spec).
 */
function formatInstant(iso) {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(iso));
  } catch {
    return String(iso);
  }
}

/**
 * Map matchStatus to a MUI Chip color.
 */
function matchStatusColor(status) {
  switch (status) {
    case 'DISCREPANCY':
      return 'warning';
    case 'MISSING_SCHEME':
      return 'error';
    case 'MISSING_INTERNAL':
      return 'info';
    default:
      return 'default';
  }
}

/**
 * Map exceptionStatus to a MUI Chip color.
 */
function exceptionStatusColor(status) {
  switch (status) {
    case 'OPEN':
      return 'error';
    case 'RESOLVED':
      return 'success';
    case 'RE_RUN':
      return 'info';
    default:
      return 'default';
  }
}

const RESOLUTION_ACTIONS = [
  { value: 'MANUAL_OVERRIDE', label: 'Manual override' },
  { value: 'RESUBMIT', label: 'Resubmit to ZeroPay' },
  { value: 'WAIVED', label: 'Waived' },
];

/**
 * Settlement Exceptions ops screen (UC-04-03, BS-04).
 *
 * Shows the recon exception queue populated by the settlement-reconciliation
 * service after comparing GME internal aggregation vs ZeroPay result files.
 *
 * Ops actions:
 *  - Resolve: opens dialog to pick resolutionAction + note, POSTs to resolve.
 *  - Re-run:  re-triggers the recon diff for one exception row.
 *
 * Money values are BigDecimal strings — rendered via MoneyDisplay, never
 * cast to Number().
 */
export default function SettlementExceptionsPage() {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { items, loading, error, acting, actError, filters } = useAppSelector(
    (s) => s.reconExceptions,
  );
  const operator = currentOperator();

  // ---------- Filter state ----------
  const [filterStatus, setFilterStatus] = useState(filters.exceptionStatus ?? '');
  const [filterMatchStatus, setFilterMatchStatus] = useState(
    filters.matchStatus ?? '',
  );
  const [filterBatchId, setFilterBatchId] = useState(filters.batchId ?? '');

  // ---------- Resolve dialog state ----------
  const [resolveDialog, setResolveDialog] = useState(null); // { id, merchantId } | null
  const [resolutionAction, setResolutionAction] = useState('');
  const [resolutionNote, setResolutionNote] = useState('');
  const [dialogTouched, setDialogTouched] = useState(false);

  // ---------- Load ----------
  const reload = useCallback(
    (overrides = {}) => {
      const f = {
        ...(filterBatchId && { batchId: filterBatchId }),
        ...(filterStatus && { exceptionStatus: filterStatus }),
        ...(filterMatchStatus && { matchStatus: filterMatchStatus }),
        ...overrides,
      };
      dispatch(listExceptions(f));
    },
    [dispatch, filterBatchId, filterStatus, filterMatchStatus],
  );

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleApplyFilters = () => reload();

  // ---------- Resolve flow ----------
  const openResolveDialog = (row) => {
    setResolveDialog({ id: row.id, merchantId: row.merchantId });
    setResolutionAction('');
    setResolutionNote('');
    setDialogTouched(false);
  };

  const closeResolveDialog = () => {
    setResolveDialog(null);
    setResolutionAction('');
    setResolutionNote('');
    setDialogTouched(false);
  };

  const handleResolveSubmit = async () => {
    setDialogTouched(true);
    if (!resolutionAction || !resolutionNote.trim()) return;
    const { id } = resolveDialog;
    closeResolveDialog();
    try {
      await dispatch(
        resolveException({
          id,
          operatorId: operator,
          note: resolutionNote.trim(),
          resolutionAction,
        }),
      ).unwrap();
      snackbar.success('Exception resolved.');
    } catch {
      snackbar.error(actError[id] ?? 'Resolve failed');
    }
  };

  // ---------- Re-run flow ----------
  const handleReRun = async (id) => {
    try {
      await dispatch(reRunException({ id, operatorId: operator })).unwrap();
      snackbar.success('Re-run queued.');
    } catch {
      snackbar.error(actError[id] ?? 'Re-run failed');
    }
  };

  // ---------- Render ----------
  const rows = Array.isArray(items) ? items : [];
  const actionMissing = dialogTouched && !resolutionAction;
  const noteMissing = dialogTouched && !resolutionNote.trim();

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h1" sx={{ flexGrow: 1 }}>
          Settlement Exceptions
        </Typography>
        <Button variant="outlined" onClick={() => reload()} disabled={loading}>
          Refresh
        </Button>
      </Box>

      {/* ---------- Filter bar ---------- */}
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction="row" spacing={2} flexWrap="wrap" alignItems="flex-end">
          <TextField
            label="Batch ID"
            size="small"
            value={filterBatchId}
            onChange={(e) => setFilterBatchId(e.target.value)}
            sx={{ minWidth: 200 }}
            inputProps={{ 'aria-label': 'batch id filter' }}
          />
          <FormControl size="small" sx={{ minWidth: 160 }}>
            <InputLabel id="exc-status-label">Status</InputLabel>
            <Select
              labelId="exc-status-label"
              label="Status"
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
              inputProps={{ 'aria-label': 'exception status filter' }}
            >
              <MenuItem value="">All</MenuItem>
              <MenuItem value="OPEN">OPEN</MenuItem>
              <MenuItem value="RESOLVED">RESOLVED</MenuItem>
              <MenuItem value="RE_RUN">RE_RUN</MenuItem>
            </Select>
          </FormControl>
          <FormControl size="small" sx={{ minWidth: 200 }}>
            <InputLabel id="match-status-label">Match status</InputLabel>
            <Select
              labelId="match-status-label"
              label="Match status"
              value={filterMatchStatus}
              onChange={(e) => setFilterMatchStatus(e.target.value)}
              inputProps={{ 'aria-label': 'match status filter' }}
            >
              <MenuItem value="">All</MenuItem>
              <MenuItem value="DISCREPANCY">DISCREPANCY</MenuItem>
              <MenuItem value="MISSING_SCHEME">MISSING_SCHEME</MenuItem>
              <MenuItem value="MISSING_INTERNAL">MISSING_INTERNAL</MenuItem>
            </Select>
          </FormControl>
          <Button variant="contained" onClick={handleApplyFilters} disabled={loading}>
            Apply
          </Button>
        </Stack>
      </Paper>

      <ErrorAlert
        message={error}
        onRetry={() => reload()}
        title="Could not load settlement exceptions"
      />

      {loading && rows.length === 0 ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : !loading && rows.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState
            heading="No exceptions found"
            description="The recon engine has not flagged any discrepancies for the current filters."
          />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Settlement exceptions">
            <TableHead>
              <TableRow>
                <TableCell>ID</TableCell>
                <TableCell>Batch</TableCell>
                <TableCell>Merchant</TableCell>
                <TableCell>Match type</TableCell>
                <TableCell align="right">GME amount</TableCell>
                <TableCell align="right">Scheme amount</TableCell>
                <TableCell align="right">Discrepancy</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Flagged at (KST)</TableCell>
                <TableCell>Resolved by</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row) => {
                const isActing = Boolean(acting[row.id]);
                const isOpen = row.exceptionStatus === 'OPEN';
                return (
                  <TableRow key={row.id} hover>
                    <TableCell>{row.id}</TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                      {row.batchId ?? '—'}
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                      {row.merchantId ?? '—'}
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={row.matchStatus ?? '—'}
                        color={matchStatusColor(row.matchStatus)}
                      />
                    </TableCell>
                    <TableCell align="right">
                      {/* gmeAmount is always present */}
                      <MoneyDisplay amount={row.gmeAmount} currency="KRW" />
                    </TableCell>
                    <TableCell align="right">
                      {row.schemeAmount != null ? (
                        <MoneyDisplay amount={row.schemeAmount} currency="KRW" />
                      ) : (
                        <Typography component="span" color="text.secondary">
                          —
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <MoneyDisplay
                        amount={row.discrepancyAmount}
                        currency="KRW"
                        negativeRed
                      />
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={row.exceptionStatus ?? '—'}
                        color={exceptionStatusColor(row.exceptionStatus)}
                      />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      {formatInstant(row.createdAt)}
                    </TableCell>
                    <TableCell>
                      {row.operatorId ? (
                        <Tooltip
                          title={
                            row.resolutionNote
                              ? `${row.resolutionAction ?? ''}: ${row.resolutionNote}`
                              : row.resolutionAction ?? ''
                          }
                        >
                          <span>{row.operatorId}</span>
                        </Tooltip>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Box
                        sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}
                      >
                        <Tooltip
                          title={
                            !isOpen
                              ? 'Only OPEN exceptions can be resolved'
                              : 'Resolve this exception'
                          }
                        >
                          <span>
                            <Button
                              size="small"
                              variant="contained"
                              color="success"
                              startIcon={<CheckCircleOutlineIcon />}
                              disabled={!isOpen || isActing}
                              aria-label={`Resolve exception ${row.id}`}
                              onClick={() => openResolveDialog(row)}
                            >
                              Resolve
                            </Button>
                          </span>
                        </Tooltip>

                        <Tooltip
                          title={
                            row.exceptionStatus === 'RESOLVED'
                              ? 'Already resolved'
                              : 'Re-run recon diff for this exception'
                          }
                        >
                          <span>
                            <Button
                              size="small"
                              variant="outlined"
                              startIcon={<ReplayIcon />}
                              disabled={row.exceptionStatus === 'RESOLVED' || isActing}
                              aria-label={`Re-run exception ${row.id}`}
                              onClick={() => handleReRun(row.id)}
                            >
                              Re-run
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

      {/* ---------- Resolve dialog ---------- */}
      <Dialog
        open={Boolean(resolveDialog)}
        onClose={closeResolveDialog}
        maxWidth="sm"
        fullWidth
        aria-labelledby="resolve-dialog-title"
      >
        <DialogTitle id="resolve-dialog-title">
          Resolve exception
          {resolveDialog?.merchantId ? ` — merchant ${resolveDialog.merchantId}` : ''}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <FormControl fullWidth error={actionMissing}>
              <InputLabel id="resolution-action-label">
                Resolution action (required)
              </InputLabel>
              <Select
                labelId="resolution-action-label"
                label="Resolution action (required)"
                value={resolutionAction}
                onChange={(e) => setResolutionAction(e.target.value)}
                inputProps={{ 'aria-label': 'resolution action' }}
              >
                {RESOLUTION_ACTIONS.map((opt) => (
                  <MenuItem key={opt.value} value={opt.value}>
                    {opt.label}
                  </MenuItem>
                ))}
              </Select>
              {actionMissing && (
                <Typography variant="caption" color="error" sx={{ mt: 0.5, ml: 1.5 }}>
                  A resolution action is required.
                </Typography>
              )}
            </FormControl>

            <TextField
              autoFocus
              fullWidth
              multiline
              minRows={3}
              label="Resolution note (required)"
              value={resolutionNote}
              onChange={(e) => setResolutionNote(e.target.value)}
              onBlur={() => setDialogTouched(true)}
              error={noteMissing}
              helperText={noteMissing ? 'A note is required before resolving.' : ''}
              inputProps={{ 'aria-label': 'resolution note' }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeResolveDialog}>Cancel</Button>
          <Button
            variant="contained"
            color="success"
            onClick={handleResolveSubmit}
            aria-label="confirm resolve"
          >
            Confirm resolve
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

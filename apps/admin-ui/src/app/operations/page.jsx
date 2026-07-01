'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  FormControl,
  Grid,
  IconButton,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import PauseCircleOutlineIcon from '@mui/icons-material/PauseCircleOutline';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import BuildCircleIcon from '@mui/icons-material/BuildCircle';
import BlockIcon from '@mui/icons-material/Block';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';
import * as opsApi from '@/api/opsApi';

const POLL_MS = 12000;

/** Format an ISO-8601 instant for display; graceful on missing/bad input. */
function fmt(iso) {
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

function severityColor(sev) {
  switch ((sev || '').toUpperCase()) {
    case 'CRITICAL':
      return 'error';
    case 'WARNING':
      return 'warning';
    case 'INFO':
      return 'info';
    default:
      return 'default';
  }
}

/** Small stat card used across the control-tower rollup grid. */
function StatCard({ label, value, sub, color }) {
  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h4" sx={{ mt: 0.5, color: color || 'text.primary' }}>
        {value}
      </Typography>
      {sub ? (
        <Typography variant="caption" color="text.secondary">
          {sub}
        </Typography>
      ) : null}
    </Paper>
  );
}

/** A section that failed to compute on the BFF renders as "unavailable". */
function UnavailableCard({ label }) {
  return (
    <Paper variant="outlined" sx={{ p: 2, height: '100%', opacity: 0.7 }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" sx={{ mt: 0.5, fontStyle: 'italic' }} color="text.secondary">
        unavailable
      </Typography>
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// Control Tower tab
// ---------------------------------------------------------------------------
function ControlTower() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const timer = useRef(null);

  const load = useCallback(async () => {
    try {
      const ct = await opsApi.getControlTower();
      setData(ct);
      setError(null);
    } catch (e) {
      setError(e.message || 'Failed to load control tower');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    timer.current = setInterval(load, POLL_MS);
    return () => clearInterval(timer.current);
  }, [load]);

  const degraded = Array.isArray(data?.degradedSections) ? data.degradedSections : [];
  const isDegraded = (key) => degraded.includes(key);

  const status = data?.operationalStatus;
  const banner = (() => {
    if (!status) return null;
    const chips = [];
    if (status.systemPaused) chips.push('SYSTEM PAUSED');
    if (status.maintenanceMode) chips.push('MAINTENANCE MODE');
    const sp = status.suspendedPartners?.length || 0;
    const ss = status.suspendedSchemes?.length || 0;
    const sr = status.suspendedRoutes?.length || 0;
    if (sp) chips.push(`${sp} partner(s) suspended`);
    if (ss) chips.push(`${ss} scheme(s) suspended`);
    if (sr) chips.push(`${sr} route(s) suspended`);
    const disrupted = status.systemPaused || status.maintenanceMode || sp || ss || sr;
    return { chips, disrupted, reason: status.reason, since: status.since };
  })();

  const health = data?.health;
  const backlog = data?.webhookBacklog;
  const float = Array.isArray(data?.floatHeadroom) ? data.floatHeadroom : [];
  const alerts = Array.isArray(data?.recentAlerts) ? data.recentAlerts : [];

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h2" sx={{ flexGrow: 1 }}>
          Control Tower
        </Typography>
        <Tooltip title="Refresh now">
          <IconButton onClick={load} aria-label="Refresh control tower">
            <RefreshIcon />
          </IconButton>
        </Tooltip>
      </Box>

      <ErrorAlert message={error} onRetry={load} title="Could not load control tower" />

      {/* Operational status banner */}
      {banner ? (
        <Alert
          severity={banner.disrupted ? 'warning' : 'success'}
          sx={{ mb: 2 }}
          aria-label="operational-status-banner"
        >
          <AlertTitle>
            {banner.disrupted ? 'Operational status: DISRUPTED' : 'Operational status: Normal'}
          </AlertTitle>
          {banner.disrupted ? (
            <Stack direction="row" spacing={1} flexWrap="wrap" sx={{ mb: 0.5 }}>
              {banner.chips.map((c) => (
                <Chip key={c} size="small" color="warning" label={c} />
              ))}
            </Stack>
          ) : (
            'All systems accepting transactions.'
          )}
          {banner.reason ? <div>Reason: {banner.reason}</div> : null}
          {banner.since ? <div>Since: {fmt(banner.since)}</div> : null}
        </Alert>
      ) : null}

      {loading && !data ? (
        <LoadingSkeleton variant="page" />
      ) : (
        <>
          {/* Rollup cards */}
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid item xs={6} md={3}>
              {isDegraded('inFlight') ? (
                <UnavailableCard label="In-flight txns" />
              ) : (
                <StatCard label="In-flight txns" value={data?.inFlight ?? '—'} />
              )}
            </Grid>
            <Grid item xs={6} md={3}>
              {isDegraded('uncertainOrAgedCount') ? (
                <UnavailableCard label="UNCERTAIN / aged" />
              ) : (
                <StatCard
                  label="UNCERTAIN / aged"
                  value={data?.uncertainOrAgedCount ?? '—'}
                  color={data?.uncertainOrAgedCount ? 'warning.main' : undefined}
                />
              )}
            </Grid>
            <Grid item xs={6} md={3}>
              {isDegraded('webhookBacklog') ? (
                <UnavailableCard label="Webhook backlog" />
              ) : (
                <StatCard
                  label="Webhook backlog"
                  value={backlog?.total ?? '—'}
                  sub={backlog ? `pending ${backlog.pending ?? 0} · dlq ${backlog.dlq ?? 0}` : undefined}
                  color={backlog?.dlq ? 'error.main' : undefined}
                />
              )}
            </Grid>
            <Grid item xs={6} md={3}>
              {isDegraded('openReconExceptions') ? (
                <UnavailableCard label="Open recon exceptions" />
              ) : (
                <StatCard
                  label="Open recon exceptions"
                  value={data?.openReconExceptions ?? '—'}
                  color={data?.openReconExceptions ? 'warning.main' : undefined}
                />
              )}
            </Grid>
            <Grid item xs={6} md={3}>
              {isDegraded('health') ? (
                <UnavailableCard label="Service health" />
              ) : (
                <StatCard
                  label="Service health"
                  value={health ? `${health.up ?? 0}/${health.total ?? 0} up` : '—'}
                  sub={health ? `down ${health.down ?? 0} · degraded ${health.degraded ?? 0}` : undefined}
                  color={health?.down ? 'error.main' : health?.degraded ? 'warning.main' : 'success.main'}
                />
              )}
            </Grid>
          </Grid>

          {/* Float headroom */}
          <Typography variant="h3" sx={{ mb: 1 }}>
            Float headroom
          </Typography>
          {isDegraded('floatHeadroom') ? (
            <Paper variant="outlined" sx={{ p: 2, mb: 2, opacity: 0.7 }}>
              <Typography variant="body2" fontStyle="italic" color="text.secondary">
                Float headroom unavailable
              </Typography>
            </Paper>
          ) : float.length === 0 ? (
            <Paper variant="outlined" sx={{ mb: 2 }}>
              <EmptyState heading="No partner float data" />
            </Paper>
          ) : (
            <TableContainer component={Paper} sx={{ mb: 3 }}>
              <Table aria-label="Float headroom" size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Partner</TableCell>
                    <TableCell align="right">Balance</TableCell>
                    <TableCell align="right">Threshold</TableCell>
                    <TableCell align="right">% of threshold</TableCell>
                    <TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {float.map((f) => (
                    <TableRow key={f.partner} hover selected={!!f.atRisk}>
                      <TableCell>{f.partner ?? '—'}</TableCell>
                      {/* Money is a decimal string on the wire — render as-is. */}
                      <TableCell align="right">{f.balance ?? '—'}</TableCell>
                      <TableCell align="right">{f.threshold ?? '—'}</TableCell>
                      <TableCell align="right">
                        {f.pctOfThreshold != null ? `${f.pctOfThreshold}%` : '—'}
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={f.atRisk ? 'AT RISK' : 'OK'}
                          color={f.atRisk ? 'error' : 'success'}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}

          {/* Recent alerts strip */}
          <Typography variant="h3" sx={{ mb: 1 }}>
            Recent alerts
          </Typography>
          {alerts.length === 0 ? (
            <Paper variant="outlined">
              <EmptyState heading="No recent alerts" />
            </Paper>
          ) : (
            <Stack spacing={1} aria-label="recent-alerts">
              {alerts.slice(0, 8).map((a, i) => (
                <Paper key={a.subjectRef ? `${a.subjectRef}-${i}` : i} variant="outlined" sx={{ p: 1.5 }}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                    <Chip size="small" label={a.severity ?? '—'} color={severityColor(a.severity)} />
                    <Typography variant="body2" fontWeight={600}>
                      {a.alertType ?? '—'}
                    </Typography>
                    {a.subjectRef ? (
                      <Typography variant="body2" color="text.secondary">
                        {a.subjectRef}
                      </Typography>
                    ) : null}
                    <Box sx={{ flexGrow: 1 }} />
                    <Typography variant="caption" color="text.secondary">
                      {fmt(a.occurredAt)}
                    </Typography>
                  </Stack>
                  {a.detail ? (
                    <Typography variant="body2" sx={{ mt: 0.5 }}>
                      {a.detail}
                    </Typography>
                  ) : null}
                </Paper>
              ))}
            </Stack>
          )}
        </>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Kill-switch tab
// ---------------------------------------------------------------------------
const ENTITY_TYPES = ['PARTNER', 'SCHEME', 'ROUTE'];

function KillSwitch() {
  const snackbar = useSnackbar();
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);

  // Pending confirm dialog: { title, body, requireReason, onConfirm } | null
  const [confirm, setConfirm] = useState(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  // Suspend form
  const [entityType, setEntityType] = useState('PARTNER');
  const [entityId, setEntityId] = useState('');

  const loadStatus = useCallback(async () => {
    try {
      const ct = await opsApi.getControlTower();
      setStatus(ct?.operationalStatus ?? null);
      setError(null);
    } catch (e) {
      setError(e.message || 'Failed to load status');
    }
  }, []);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  const run = async (fn, successMsg) => {
    setBusy(true);
    try {
      await fn();
      snackbar.success(successMsg);
      setConfirm(null);
      setReason('');
      await loadStatus();
    } catch (e) {
      snackbar.error(e.message || 'Action failed');
    } finally {
      setBusy(false);
    }
  };

  const askPause = () =>
    setConfirm({
      title: 'Pause the entire system?',
      body: 'This halts ALL transaction processing platform-wide. A reason is required.',
      requireReason: true,
      confirmLabel: 'Pause system',
      color: 'error',
      onConfirm: (r) => run(() => opsApi.pause(r), 'System paused.'),
    });

  const askResume = () =>
    setConfirm({
      title: 'Resume the system?',
      body: 'This re-enables platform-wide transaction processing.',
      confirmLabel: 'Resume system',
      color: 'success',
      onConfirm: () => run(() => opsApi.resume(), 'System resumed.'),
    });

  const askMaintenance = (on) =>
    setConfirm({
      title: on ? 'Enable maintenance mode?' : 'Disable maintenance mode?',
      body: on
        ? 'Maintenance mode surfaces a maintenance banner and may reject new work.'
        : 'This turns maintenance mode off.',
      requireReason: on,
      confirmLabel: on ? 'Enable maintenance' : 'Disable maintenance',
      color: on ? 'warning' : 'success',
      onConfirm: (r) =>
        run(() => opsApi.setMaintenance(on, r), on ? 'Maintenance mode enabled.' : 'Maintenance mode disabled.'),
    });

  const askSuspend = () => {
    if (!entityId.trim()) {
      snackbar.error('Enter an entity id to suspend.');
      return;
    }
    setConfirm({
      title: `Suspend ${entityType} "${entityId.trim()}"?`,
      body: 'This blocks the entity from processing until it is unsuspended. A reason is required.',
      requireReason: true,
      confirmLabel: 'Suspend',
      color: 'error',
      onConfirm: (r) =>
        run(() => opsApi.suspend(entityType, entityId.trim(), r), `${entityType} ${entityId.trim()} suspended.`),
    });
  };

  const askUnsuspend = () => {
    if (!entityId.trim()) {
      snackbar.error('Enter an entity id to unsuspend.');
      return;
    }
    setConfirm({
      title: `Unsuspend ${entityType} "${entityId.trim()}"?`,
      body: 'This restores normal processing for the entity.',
      confirmLabel: 'Unsuspend',
      color: 'success',
      onConfirm: () =>
        run(() => opsApi.unsuspend(entityType, entityId.trim()), `${entityType} ${entityId.trim()} unsuspended.`),
    });
  };

  const paused = status?.systemPaused;
  const maint = status?.maintenanceMode;
  const reasonMissing = confirm?.requireReason && !reason.trim();

  return (
    <Box>
      <Typography variant="h2" sx={{ mb: 1 }}>
        Kill-switch controls
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Dangerous, money-affecting actions. Each is confirmed and requires the
        <code> ops:operate</code> permission (sent to the fail-closed BFF).
      </Typography>

      <ErrorAlert message={error} onRetry={loadStatus} title="Could not load status" severity="warning" />

      {/* Live status banner (refreshed after each action) */}
      <Alert severity={paused || maint ? 'warning' : 'success'} sx={{ mb: 2 }} aria-label="killswitch-status">
        <AlertTitle>Current status</AlertTitle>
        System: <b>{paused ? 'PAUSED' : 'RUNNING'}</b> · Maintenance: <b>{maint ? 'ON' : 'OFF'}</b>
        {status?.reason ? <div>Reason: {status.reason}</div> : null}
        {status?.since ? <div>Since: {fmt(status.since)}</div> : null}
      </Alert>

      <Grid container spacing={2}>
        <Grid item xs={12} md={6}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h3" sx={{ mb: 1.5 }}>
              System
            </Typography>
            <Stack direction="row" spacing={1.5} flexWrap="wrap">
              <Button
                variant="contained"
                color="error"
                startIcon={<PauseCircleOutlineIcon />}
                onClick={askPause}
                disabled={paused}
              >
                Pause
              </Button>
              <Button
                variant="contained"
                color="success"
                startIcon={<PlayCircleOutlineIcon />}
                onClick={askResume}
                disabled={!paused}
              >
                Resume
              </Button>
              <Button
                variant="outlined"
                color="warning"
                startIcon={<BuildCircleIcon />}
                onClick={() => askMaintenance(!maint)}
              >
                {maint ? 'Maintenance off' : 'Maintenance on'}
              </Button>
            </Stack>
          </Paper>
        </Grid>

        <Grid item xs={12} md={6}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h3" sx={{ mb: 1.5 }}>
              Suspend / Unsuspend
            </Typography>
            <Stack direction="row" spacing={1.5} flexWrap="wrap" alignItems="center">
              <FormControl size="small" sx={{ minWidth: 130 }}>
                <InputLabel id="entity-type-label">Type</InputLabel>
                <Select
                  labelId="entity-type-label"
                  label="Type"
                  value={entityType}
                  onChange={(e) => setEntityType(e.target.value)}
                  inputProps={{ 'aria-label': 'entity type' }}
                >
                  {ENTITY_TYPES.map((t) => (
                    <MenuItem key={t} value={t}>
                      {t}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <TextField
                size="small"
                label="Entity id"
                value={entityId}
                onChange={(e) => setEntityId(e.target.value)}
                inputProps={{ 'aria-label': 'entity id' }}
              />
              <Button variant="contained" color="error" startIcon={<BlockIcon />} onClick={askSuspend}>
                Suspend
              </Button>
              <Button variant="outlined" color="success" onClick={askUnsuspend}>
                Unsuspend
              </Button>
            </Stack>
          </Paper>
        </Grid>
      </Grid>

      {/* Confirm dialog (shared for all kill-switch actions) */}
      <Dialog
        open={Boolean(confirm)}
        onClose={() => (busy ? null : setConfirm(null))}
        maxWidth="sm"
        fullWidth
        aria-labelledby="killswitch-confirm-title"
      >
        <DialogTitle id="killswitch-confirm-title">{confirm?.title}</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: confirm?.requireReason ? 2 : 0 }}>{confirm?.body}</DialogContentText>
          {confirm?.requireReason ? (
            <TextField
              autoFocus
              fullWidth
              multiline
              minRows={2}
              label="Reason (required)"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              error={reasonMissing && reason !== ''}
              inputProps={{ 'aria-label': 'action reason' }}
            />
          ) : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(null)} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color={confirm?.color || 'primary'}
            disabled={busy || reasonMissing}
            onClick={() => confirm?.onConfirm(reason.trim())}
            aria-label="confirm action"
          >
            {confirm?.confirmLabel || 'Confirm'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Alerts tab
// ---------------------------------------------------------------------------
function AlertsTab() {
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [severity, setSeverity] = useState('');
  const [type, setType] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const rows = await opsApi.getAlerts({ severity, type, limit: 50 });
      setAlerts(Array.isArray(rows) ? rows : []);
      setError(null);
    } catch (e) {
      setError(e.message || 'Failed to load alerts');
    } finally {
      setLoading(false);
    }
  }, [severity, type]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
        <Typography variant="h2" sx={{ flexGrow: 1 }}>
          Alerts
        </Typography>
        <Tooltip title="Refresh alerts">
          <IconButton onClick={load} aria-label="Refresh alerts">
            <RefreshIcon />
          </IconButton>
        </Tooltip>
      </Box>

      <Stack direction="row" spacing={2} sx={{ mb: 2 }} flexWrap="wrap">
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel id="alert-sev-label">Severity</InputLabel>
          <Select
            labelId="alert-sev-label"
            label="Severity"
            value={severity}
            onChange={(e) => setSeverity(e.target.value)}
            inputProps={{ 'aria-label': 'filter by severity' }}
          >
            <MenuItem value="">All</MenuItem>
            <MenuItem value="CRITICAL">Critical</MenuItem>
            <MenuItem value="WARNING">Warning</MenuItem>
            <MenuItem value="INFO">Info</MenuItem>
          </Select>
        </FormControl>
        <TextField
          size="small"
          label="Type"
          value={type}
          onChange={(e) => setType(e.target.value)}
          placeholder="e.g. FLOAT_LOW"
          inputProps={{ 'aria-label': 'filter by type' }}
        />
      </Stack>

      <ErrorAlert message={error} onRetry={load} title="Could not load alerts" />

      {loading && alerts.length === 0 ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : !loading && alerts.length === 0 && !error ? (
        <Paper variant="outlined">
          <EmptyState heading="No alerts match the current filters" />
        </Paper>
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="Ops alerts">
            <TableHead>
              <TableRow>
                <TableCell>Severity</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Subject</TableCell>
                <TableCell>Detail</TableCell>
                <TableCell>Occurred at</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {alerts.map((a, i) => (
                <TableRow key={a.subjectRef ? `${a.subjectRef}-${i}` : i} hover>
                  <TableCell>
                    <Chip size="small" label={a.severity ?? '—'} color={severityColor(a.severity)} />
                  </TableCell>
                  <TableCell>{a.alertType ?? '—'}</TableCell>
                  <TableCell>{a.subjectRef ?? '—'}</TableCell>
                  <TableCell>{a.detail ?? '—'}</TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{fmt(a.occurredAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Transactions & Recovery tab
// ---------------------------------------------------------------------------
function TxnRecovery() {
  const snackbar = useSnackbar();
  const [filters, setFilters] = useState({ txnRef: '', partnerId: '', status: '', from: '', to: '' });
  const [rows, setRows] = useState(null); // null = not searched yet
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Resolve dialog: { ref } | null
  const [resolveTgt, setResolveTgt] = useState(null);
  const [resolution, setResolution] = useState('COMPLETED');
  const [resolveReason, setResolveReason] = useState('');
  const [busy, setBusy] = useState(false);

  // Recovery inputs
  const [webhookId, setWebhookId] = useState('');
  const [reconBatchId, setReconBatchId] = useState('');
  const [reconDate, setReconDate] = useState('');

  const setF = (k) => (e) => setFilters((f) => ({ ...f, [k]: e.target.value }));

  const doSearch = useCallback(async () => {
    setLoading(true);
    try {
      const page = await opsApi.searchTransactions(filters);
      const content = Array.isArray(page?.content) ? page.content : Array.isArray(page) ? page : [];
      setRows(content);
      setError(null);
    } catch (e) {
      setError(e.message || 'Search failed');
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [filters]);

  const openResolve = (ref) => {
    setResolveTgt({ ref });
    setResolution('COMPLETED');
    setResolveReason('');
  };

  const submitResolve = async () => {
    if (!resolveReason.trim()) return;
    const { ref } = resolveTgt;
    setBusy(true);
    try {
      await opsApi.resolveTransaction(ref, resolution, resolveReason.trim());
      snackbar.success(`Transaction ${ref} resolved as ${resolution}.`);
      setResolveTgt(null);
      doSearch();
    } catch (e) {
      snackbar.error(e.message || 'Resolve failed');
    } finally {
      setBusy(false);
    }
  };

  const doReplay = async () => {
    if (!webhookId.trim()) {
      snackbar.error('Enter a webhook id.');
      return;
    }
    try {
      await opsApi.replayWebhook(webhookId.trim());
      snackbar.success(`Webhook ${webhookId.trim()} queued for replay.`);
      setWebhookId('');
    } catch (e) {
      snackbar.error(e.message || 'Replay failed');
    }
  };

  const doRerun = async () => {
    const body = {};
    if (reconBatchId.trim()) body.batchId = reconBatchId.trim();
    if (reconDate.trim()) body.settlementDate = reconDate.trim();
    if (!body.batchId && !body.settlementDate) {
      snackbar.error('Enter a batch id or settlement date.');
      return;
    }
    try {
      await opsApi.rerunRecon(body);
      snackbar.success('Reconciliation re-run triggered.');
      setReconBatchId('');
      setReconDate('');
    } catch (e) {
      snackbar.error(e.message || 'Recon re-run failed');
    }
  };

  const resolveReasonMissing = resolveReason === '' ? false : !resolveReason.trim();

  return (
    <Box>
      <Typography variant="h2" sx={{ mb: 1 }}>
        Transaction search &amp; recovery
      </Typography>

      {/* Search form */}
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction="row" spacing={2} flexWrap="wrap" alignItems="center">
          <TextField size="small" label="Txn ref" value={filters.txnRef} onChange={setF('txnRef')}
            inputProps={{ 'aria-label': 'txn ref' }} />
          <TextField size="small" label="Partner id" value={filters.partnerId} onChange={setF('partnerId')}
            inputProps={{ 'aria-label': 'partner id' }} />
          <TextField size="small" label="Status" value={filters.status} onChange={setF('status')}
            inputProps={{ 'aria-label': 'status' }} />
          <TextField size="small" type="date" label="From" InputLabelProps={{ shrink: true }}
            value={filters.from} onChange={setF('from')} inputProps={{ 'aria-label': 'from date' }} />
          <TextField size="small" type="date" label="To" InputLabelProps={{ shrink: true }}
            value={filters.to} onChange={setF('to')} inputProps={{ 'aria-label': 'to date' }} />
          <Button variant="contained" onClick={doSearch} disabled={loading}>
            Search
          </Button>
        </Stack>
      </Paper>

      <ErrorAlert message={error} onRetry={doSearch} title="Transaction search failed" />

      {loading && (!rows || rows.length === 0) ? (
        <LoadingSkeleton variant="table" rows={5} />
      ) : rows === null ? (
        <Paper variant="outlined">
          <EmptyState heading="Search for transactions" description="Enter filters above and hit Search." />
        </Paper>
      ) : rows.length === 0 ? (
        <Paper variant="outlined">
          <EmptyState heading="No transactions match" description="Adjust the filters and search again." />
        </Paper>
      ) : (
        <TableContainer component={Paper} sx={{ mb: 3 }}>
          <Table aria-label="Transaction search results">
            <TableHead>
              <TableRow>
                <TableCell>Txn ref</TableCell>
                <TableCell>Partner</TableCell>
                <TableCell>State</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell>Committed</TableCell>
                <TableCell align="right">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((t) => {
                const ref = t.txnRef ?? t.txnId;
                return (
                  <TableRow key={ref} hover>
                    <TableCell>{ref ?? '—'}</TableCell>
                    <TableCell>{t.partnerId ?? '—'}</TableCell>
                    <TableCell>
                      <Chip size="small" label={t.state ?? t.status ?? '—'} />
                    </TableCell>
                    {/* amount is a decimal string — render as-is */}
                    <TableCell align="right">
                      {t.amount ?? '—'} {t.currency ?? ''}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{fmt(t.committedAt)}</TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={() => openResolve(ref)}
                        aria-label={`resolve ${ref}`}
                      >
                        Resolve
                      </Button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <Divider sx={{ my: 3 }} />

      {/* Recovery area */}
      <Typography variant="h3" sx={{ mb: 1.5 }}>
        Recovery
      </Typography>
      <Grid container spacing={2}>
        <Grid item xs={12} md={6}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="body2" fontWeight={600} sx={{ mb: 1 }}>
              Webhook replay
            </Typography>
            <Stack direction="row" spacing={1.5} alignItems="center">
              <TextField
                size="small"
                label="Webhook id"
                value={webhookId}
                onChange={(e) => setWebhookId(e.target.value)}
                inputProps={{ 'aria-label': 'webhook id' }}
              />
              <Button variant="contained" onClick={doReplay}>
                Replay
              </Button>
            </Stack>
          </Paper>
        </Grid>
        <Grid item xs={12} md={6}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="body2" fontWeight={600} sx={{ mb: 1 }}>
              Recon re-run
            </Typography>
            <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap">
              <TextField
                size="small"
                label="Batch id"
                value={reconBatchId}
                onChange={(e) => setReconBatchId(e.target.value)}
                inputProps={{ 'aria-label': 'recon batch id' }}
              />
              <TextField
                size="small"
                type="date"
                label="Settlement date"
                InputLabelProps={{ shrink: true }}
                value={reconDate}
                onChange={(e) => setReconDate(e.target.value)}
                inputProps={{ 'aria-label': 'settlement date' }}
              />
              <Button variant="contained" onClick={doRerun}>
                Re-run
              </Button>
            </Stack>
          </Paper>
        </Grid>
      </Grid>

      {/* Resolve dialog */}
      <Dialog
        open={Boolean(resolveTgt)}
        onClose={() => (busy ? null : setResolveTgt(null))}
        maxWidth="sm"
        fullWidth
        aria-labelledby="resolve-dialog-title"
      >
        <DialogTitle id="resolve-dialog-title">
          Resolve transaction{resolveTgt?.ref ? ` — ${resolveTgt.ref}` : ''}
        </DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            Force the terminal state for this transaction. This is a money-affecting recovery action.
          </DialogContentText>
          <FormControl size="small" sx={{ minWidth: 200, mb: 2 }}>
            <InputLabel id="resolution-label">Resolution</InputLabel>
            <Select
              labelId="resolution-label"
              label="Resolution"
              value={resolution}
              onChange={(e) => setResolution(e.target.value)}
              inputProps={{ 'aria-label': 'resolution' }}
            >
              <MenuItem value="COMPLETED">COMPLETED</MenuItem>
              <MenuItem value="REVERSED">REVERSED</MenuItem>
            </Select>
          </FormControl>
          <TextField
            fullWidth
            multiline
            minRows={2}
            label="Reason (required)"
            value={resolveReason}
            onChange={(e) => setResolveReason(e.target.value)}
            error={resolveReasonMissing}
            helperText={resolveReasonMissing ? 'A reason is required.' : ''}
            inputProps={{ 'aria-label': 'resolve reason' }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setResolveTgt(null)} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color="warning"
            onClick={submitResolve}
            disabled={busy || !resolveReason.trim()}
            aria-label="confirm resolve"
          >
            Resolve
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ---------------------------------------------------------------------------
// Page shell with tabs
// ---------------------------------------------------------------------------
const TABS = ['control-tower', 'kill-switch', 'alerts', 'transactions'];

/**
 * Operations console — the live control surface for GMEPay+ ops.
 *
 * Native React (no iframe); every call goes through the api client at
 * `/api/...` (server-side rewritten in next.config to the ops-BFF, so it works
 * over the Cloudflare tunnel). Money-affecting actions carry the
 * `ops:operate` permission header the fail-closed BFF requires — see opsApi.js.
 */
export default function OperationsPage() {
  const [tab, setTab] = useState(0);

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Operations
      </Typography>

      <Tabs
        value={tab}
        onChange={(_, v) => setTab(v)}
        sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}
        aria-label="operations sections"
      >
        <Tab label="Control Tower" />
        <Tab label="Kill-switch" />
        <Tab label="Alerts" />
        <Tab label="Transactions & Recovery" />
      </Tabs>

      {tab === 0 && <ControlTower />}
      {tab === 1 && <KillSwitch />}
      {tab === 2 && <AlertsTab />}
      {tab === 3 && <TxnRecovery />}
    </Box>
  );
}

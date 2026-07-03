'use client';

/**
 * E2E Test — native admin console.
 *
 * Lets anyone pick a country / partner / amount / MPM type, run the FULL payment
 * journey end-to-end against the fleet, and watch a step-by-step pass/fail log.
 * Every run is saved and browsable in the history table.
 *
 * All fetches use SAME-ORIGIN relative paths under `/e2e/...`. The Next node
 * server ("npm start") proxies those to the payment-executor via the rewrite in
 * next.config.mjs (PAYMENT_EXECUTOR_URL → /v1/sandbox/e2e/*, server-side), so
 * the console works over the Cloudflare tunnel too (no client-side localhost).
 *
 * Backend endpoints proxied:
 *   GET  /e2e/options       — {countries,partners,mpmTypes}
 *   POST /e2e/run           — body {country,partner,amount,mpmType} → RunDetail
 *   GET  /e2e/runs?limit=50 — [RunSummary]
 *   GET  /e2e/runs/{id}     — RunDetail
 */

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  FormControl,
  FormControlLabel,
  FormLabel,
  Grid,
  IconButton,
  MenuItem,
  Paper,
  Radio,
  RadioGroup,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CancelIcon from '@mui/icons-material/Cancel';
import RemoveCircleOutlineIcon from '@mui/icons-material/RemoveCircleOutline';
import RefreshIcon from '@mui/icons-material/Refresh';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';

const PROXY = '/e2e';

// Fallback options used only when GET /e2e/options can't be reached.
const FALLBACK_OPTIONS = {
  countries: [
    { code: 'NP', label: 'Nepal', currency: 'NPR' },
    { code: 'KR', label: 'Korea', currency: 'KRW' },
  ],
  partners: [
    { code: 'GMEREMIT', label: 'GMEREMIT' },
    { code: 'SENDMN', label: 'SENDMN' },
  ],
  mpmTypes: ['STATIC', 'DYNAMIC'],
};

async function api(path, opts) {
  const r = await fetch(path, opts);
  let body = null;
  try {
    body = await r.json();
  } catch {
    body = null;
  }
  return { ok: r.ok, status: r.status, body };
}

function timeStr(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString('en-GB', { hour12: false });
}

function stepColor(status) {
  const s = String(status || '').toUpperCase();
  if (s === 'PASS') return 'success.main';
  if (s === 'FAIL') return 'error.main';
  return 'text.disabled';
}

function StepIcon({ status }) {
  const s = String(status || '').toUpperCase();
  if (s === 'PASS') return <CheckCircleIcon fontSize="small" sx={{ color: 'success.main' }} />;
  if (s === 'FAIL') return <CancelIcon fontSize="small" sx={{ color: 'error.main' }} />;
  return <RemoveCircleOutlineIcon fontSize="small" sx={{ color: 'text.disabled' }} />;
}

// One step row in the step list.
function StepRow({ step }) {
  const failed = String(step.status || '').toUpperCase() === 'FAIL';
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1.5,
        px: 1.5,
        py: 1,
        borderRadius: 1,
        bgcolor: failed ? 'error.lighter' : 'transparent',
        border: '1px solid',
        borderColor: failed ? 'error.light' : 'divider',
      }}
    >
      <Box sx={{ pt: 0.25 }}>
        <StepIcon status={step.status} />
      </Box>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography
          sx={{ fontWeight: failed ? 700 : 600, color: stepColor(step.status), fontSize: '0.9rem' }}
        >
          {step.seq != null ? `${step.seq}. ` : ''}
          {step.name}
        </Typography>
        {step.detail && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
            {step.detail}
          </Typography>
        )}
      </Box>
      <Stack alignItems="flex-end" spacing={0.25} sx={{ flexShrink: 0 }}>
        {step.latencyMs != null && (
          <Typography variant="caption" color="text.disabled">
            {step.latencyMs} ms
          </Typography>
        )}
        {step.httpStatus != null && (
          <Typography variant="caption" color="text.disabled" sx={{ fontFamily: 'monospace' }}>
            HTTP {step.httpStatus}
          </Typography>
        )}
      </Stack>
    </Box>
  );
}

// The step list for a run (used both in the result panel and expanded history rows).
function StepList({ steps }) {
  if (!steps || steps.length === 0) {
    return (
      <Typography variant="body2" color="text.disabled">
        No steps recorded.
      </Typography>
    );
  }
  return (
    <Stack spacing={1}>
      {steps.map((s, i) => (
        <StepRow key={s.seq ?? i} step={s} />
      ))}
    </Stack>
  );
}

function ResultBanner({ run }) {
  const pass = String(run.status || '').toUpperCase() === 'PASS';
  return (
    <Alert
      severity={pass ? 'success' : 'error'}
      icon={false}
      sx={{ '& .MuiAlert-message': { width: '100%' } }}
    >
      <Typography variant="h6" component="div" sx={{ fontWeight: 700 }}>
        {pass ? '✅ Payment journey passed' : `❌ Failed at: ${run.failedStep || 'unknown step'}`}
      </Typography>
      <Typography variant="body2" sx={{ mt: 0.5 }}>
        {run.country} · {run.partner} · {run.amount} {run.currency} · {run.mpmType} ·{' '}
        {run.stepCount != null ? `${run.stepCount} steps` : ''}
      </Typography>
    </Alert>
  );
}

function ResultChip({ status }) {
  const pass = String(status || '').toUpperCase() === 'PASS';
  return <Chip size="small" label={pass ? 'PASS' : 'FAIL'} color={pass ? 'success' : 'error'} />;
}

// One history row (summary) that expands to show its steps when clicked.
function HistoryRow({ summary, onExpand }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(false);

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && !detail) {
      setLoading(true);
      const d = await onExpand(summary.id);
      setDetail(d);
      setLoading(false);
    }
  }

  return (
    <>
      <TableRow
        hover
        onClick={toggle}
        sx={{ cursor: 'pointer', '& > *': { borderBottom: 'unset' } }}
        data-testid={`history-row-${summary.id}`}
      >
        <TableCell>{timeStr(summary.createdAt)}</TableCell>
        <TableCell>{summary.country}</TableCell>
        <TableCell>{summary.partner}</TableCell>
        <TableCell align="right">
          {summary.amount} {summary.currency}
        </TableCell>
        <TableCell>{summary.mpmType}</TableCell>
        <TableCell>
          <ResultChip status={summary.status} />
        </TableCell>
        <TableCell sx={{ color: 'error.main' }}>{summary.failedStep || '—'}</TableCell>
      </TableRow>
      <TableRow>
        <TableCell colSpan={7} sx={{ py: 0, border: 0 }}>
          <Collapse in={open} unmountOnExit>
            <Box sx={{ py: 2 }}>
              {loading ? (
                <Stack direction="row" spacing={1} alignItems="center">
                  <CircularProgress size={18} />
                  <Typography variant="body2">Loading steps…</Typography>
                </Stack>
              ) : (
                <StepList steps={detail?.steps} />
              )}
            </Box>
          </Collapse>
        </TableCell>
      </TableRow>
    </>
  );
}

export default function E2eTestConsole() {
  const [options, setOptions] = useState(FALLBACK_OPTIONS);
  const [country, setCountry] = useState('NP');
  const [partner, setPartner] = useState('GMEREMIT');
  const [mpmType, setMpmType] = useState('STATIC');
  const [amount, setAmount] = useState('100');

  const [running, setRunning] = useState(false);
  const [runStatus, setRunStatus] = useState(null); // {type,msg}
  const [result, setResult] = useState(null); // RunDetail

  const [history, setHistory] = useState([]);
  const [historyError, setHistoryError] = useState(null);
  const [historyLoading, setHistoryLoading] = useState(false);

  // Load option lists; fall back silently to NP/KR + GMEREMIT/SENDMN on failure.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api(`${PROXY}/options`);
        if (!cancelled && res.ok && res.body && Array.isArray(res.body.countries)) {
          setOptions({
            countries: res.body.countries?.length ? res.body.countries : FALLBACK_OPTIONS.countries,
            partners: res.body.partners?.length ? res.body.partners : FALLBACK_OPTIONS.partners,
            mpmTypes: res.body.mpmTypes?.length ? res.body.mpmTypes : FALLBACK_OPTIONS.mpmTypes,
          });
        }
      } catch {
        // keep fallback
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const res = await api(`${PROXY}/runs?limit=50`);
      if (!res.ok || !Array.isArray(res.body)) {
        setHistory([]);
        setHistoryError('Could not load past runs.');
      } else {
        setHistory(res.body);
      }
    } catch (e) {
      setHistory([]);
      setHistoryError("Couldn't reach the test runner — is the fleet running?");
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  const loadRunDetail = useCallback(async (id) => {
    try {
      const res = await api(`${PROXY}/runs/${id}`);
      return res.ok ? res.body : null;
    } catch {
      return null;
    }
  }, []);

  async function runTest() {
    const amt = Number(amount);
    if (!String(amount).trim() || isNaN(amt) || amt <= 0) {
      setRunStatus({ type: 'error', msg: 'Enter an amount greater than zero.' });
      return;
    }
    setRunning(true);
    setRunStatus({ type: 'info', msg: 'Running the full payment journey…' });
    try {
      const res = await api(`${PROXY}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ country, partner, amount: amt, mpmType }),
      });
      if (!res.ok || !res.body) {
        const detail = res.body && (res.body.detail || JSON.stringify(res.body));
        setResult(null);
        setRunStatus({ type: 'error', msg: 'Test run failed to start: ' + (detail || res.status) });
        return;
      }
      setResult(res.body);
      const pass = String(res.body.status || '').toUpperCase() === 'PASS';
      setRunStatus({
        type: pass ? 'success' : 'error',
        msg: pass ? 'Payment journey passed.' : 'Failed at: ' + (res.body.failedStep || 'unknown'),
      });
      loadHistory();
    } catch (e) {
      setResult(null);
      setRunStatus({ type: 'error', msg: "Couldn't reach the test runner — is the fleet running?" });
    } finally {
      setRunning(false);
    }
  }

  const currencyFor = (code) =>
    options.countries.find((c) => c.code === code)?.currency || '';

  return (
    <Box sx={{ py: 1 }}>
      <Grid container spacing={2} alignItems="flex-start">
        {/* Run form + result */}
        <Grid item xs={12} md={6}>
          <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
            <Typography variant="h6" color="error.main" gutterBottom>
              Run a payment test
            </Typography>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 2 }}>
              This runs a real payment from start to finish — creating the QR, paying it, and
              checking settlement — then shows you exactly where it passed or failed. Pick a few
              options and press <strong>Run test</strong>.
            </Typography>

            <Grid container spacing={2}>
              <Grid item xs={12} sm={6}>
                <TextField
                  select
                  label="Country"
                  value={country}
                  onChange={(e) => setCountry(e.target.value)}
                  fullWidth
                >
                  {options.countries.map((c) => (
                    <MenuItem key={c.code} value={c.code}>
                      {c.label} ({c.currency})
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  select
                  label="Partner"
                  value={partner}
                  onChange={(e) => setPartner(e.target.value)}
                  fullWidth
                >
                  {options.partners.map((p) => (
                    <MenuItem key={p.code} value={p.code}>
                      {p.label}
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid item xs={12}>
                <FormControl>
                  <FormLabel sx={{ fontSize: '0.8rem' }}>QR type</FormLabel>
                  <RadioGroup
                    row
                    value={mpmType}
                    onChange={(e) => setMpmType(e.target.value)}
                    name="mpm-type"
                  >
                    <FormControlLabel
                      value="STATIC"
                      control={<Radio size="small" />}
                      label="Static (I enter the amount)"
                    />
                    <FormControlLabel
                      value="DYNAMIC"
                      control={<Radio size="small" />}
                      label="Dynamic (amount is in the QR)"
                    />
                  </RadioGroup>
                </FormControl>
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  label={`Amount${currencyFor(country) ? ` (${currencyFor(country)})` : ''}`}
                  type="number"
                  required
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  fullWidth
                  inputProps={{ min: 0.01, step: 0.01 }}
                  helperText={
                    mpmType === 'DYNAMIC'
                      ? 'Used to build the dynamic QR the test scans.'
                      : 'The amount you enter at the wallet.'
                  }
                />
              </Grid>
            </Grid>

            <Box sx={{ mt: 2 }}>
              <Button
                variant="contained"
                color="error"
                size="large"
                onClick={runTest}
                disabled={running}
                startIcon={
                  running ? <CircularProgress size={18} color="inherit" /> : <PlayArrowIcon />
                }
              >
                {running ? 'Running…' : 'Run test'}
              </Button>
            </Box>

            {runStatus && (
              <Alert severity={runStatus.type} sx={{ mt: 2 }}>
                {runStatus.msg}
              </Alert>
            )}
          </Paper>

          {result && (
            <Paper variant="outlined" sx={{ p: 2 }}>
              <Typography variant="h6" color="error.main" gutterBottom>
                Result
              </Typography>
              <Box sx={{ mb: 2 }}>
                <ResultBanner run={result} />
              </Box>
              <StepList steps={result.steps} />
            </Paper>
          )}
        </Grid>

        {/* History */}
        <Grid item xs={12} md={6}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
              <Typography variant="h6" color="error.main">
                Past runs
              </Typography>
              <IconButton size="small" onClick={loadHistory} aria-label="Refresh past runs">
                {historyLoading ? <CircularProgress size={18} /> : <RefreshIcon fontSize="small" />}
              </IconButton>
            </Stack>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
              Every test run is saved here (newest first). Click a row to see its steps.
            </Typography>
            {historyError && (
              <Alert severity="error" sx={{ mb: 1 }}>
                {historyError}
              </Alert>
            )}
            {!historyError && history.length === 0 && !historyLoading && (
              <Typography variant="body2" color="text.disabled" sx={{ textAlign: 'center', py: 2 }}>
                No runs yet — run a test to populate.
              </Typography>
            )}
            {history.length > 0 && (
              <Box sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Time</TableCell>
                      <TableCell>Country</TableCell>
                      <TableCell>Partner</TableCell>
                      <TableCell align="right">Amount</TableCell>
                      <TableCell>MPM</TableCell>
                      <TableCell>Result</TableCell>
                      <TableCell>Failed step</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {history.map((s) => (
                      <HistoryRow key={s.id} summary={s} onExpand={loadRunDetail} />
                    ))}
                  </TableBody>
                </Table>
              </Box>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
}

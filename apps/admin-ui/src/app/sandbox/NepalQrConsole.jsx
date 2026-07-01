'use client';

/**
 * Nepal QR — native admin console.
 *
 * This is a React port of the sim's own operator UI (simulators/sim-nepal-qr/
 * src/main/resources/static/{index.html,app.js}). It replaces the old
 * `<iframe src="http://localhost:9103">` tab, which showed nothing when the
 * admin portal was reached remotely (the CLIENT browser resolved "localhost").
 *
 * All fetches use SAME-ORIGIN relative paths under `/sim-nepal-qr/...`. The Next
 * node server ("npm start") proxies those to the sim via the rewrite in
 * next.config.mjs (SIM_NEPAL_QR_URL, server-side), so the calls work over the
 * Cloudflare tunnel too.
 *
 * Sim endpoints proxied:
 *   POST /sim-nepal-qr/qrscan-thirdparty/parse/  — decode a QR (body {qs})
 *   POST /sim-nepal-qr/sim/nepal-qr/ui/pay       — scan & pay convenience wrapper
 *   GET  /sim-nepal-qr/sim/nepal-qr/records      — stored request/response records
 */

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  Divider,
  Grid,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import RefreshIcon from '@mui/icons-material/Refresh';

// Sample Fonepay QR (static, no amount tag) — from the sim's app.js.
const SAMPLE_QR =
  '00020101021126350011fonepay.com071640897200000017835204541253035245802NP' +
  '5914SudanMerchant6015AathraiTriveni62060702316304d60f';

const PROXY = '/sim-nepal-qr';

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

function paisaToNpr(paisa) {
  if (paisa == null || paisa === '') return '';
  const n = Number(paisa);
  return (
    'रू ' +
    (n / 100).toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
  );
}

function newReference() {
  return (
    'UI-' +
    Date.now().toString(36).toUpperCase() +
    '-' +
    Math.random().toString(36).slice(2, 8).toUpperCase()
  );
}

function networkFromExtra(guid) {
  const g = String(guid || '').toLowerCase();
  if (g.includes('fonepay')) return 'fonepay';
  if (g.includes('nepalpay')) return 'nepalpay';
  if (g.includes('unionpay') || g.includes('cup')) return 'unionpay';
  if (g.includes('smart')) return 'smartqr';
  return guid || 'fonepay';
}

function firstMerchantId(tags) {
  if (!tags) return '';
  // Merchant Account Info templates live in tags 26..51.
  for (let t = 26; t <= 51; t++) {
    const key = String(t).padStart(2, '0');
    if (tags[key]) return tags[key];
  }
  return '';
}

function timeStr(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB', { hour12: false });
}

function statusColor(status) {
  const s = String(status || '').toUpperCase();
  if (s === 'APPROVED') return 'success';
  if (s === 'PENDING') return 'warning';
  if (s === 'REJECTED') return 'error';
  return 'default';
}

function KV({ k, v, accent }) {
  if (v == null || v === '') return null;
  return (
    <Box
      sx={{
        display: 'flex',
        gap: 1,
        py: 0.5,
        borderBottom: '1px solid',
        borderColor: 'divider',
        fontSize: '0.84rem',
      }}
    >
      <Box sx={{ color: 'text.secondary', minWidth: 150 }}>{k}</Box>
      <Box
        sx={{
          fontFamily: 'monospace',
          wordBreak: 'break-all',
          color: accent ? 'error.main' : 'text.primary',
          fontWeight: accent ? 700 : 400,
        }}
      >
        {v}
      </Box>
    </Box>
  );
}

function RecordItem({ rec }) {
  const [open, setOpen] = useState(false);
  const is2xx = rec.responseStatus >= 200 && rec.responseStatus < 300;
  const meta = [
    rec.reference ? 'ref=' + rec.reference : null,
    rec.idx ? 'idx=' + rec.idx : null,
    rec.state || null,
    timeStr(rec.receivedAt),
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      <Box
        onClick={() => setOpen((o) => !o)}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          px: 1.5,
          py: 1,
          cursor: 'pointer',
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        <Typography
          sx={{ fontFamily: 'monospace', fontWeight: 700, fontSize: '0.8rem', flexGrow: 1 }}
        >
          {rec.endpoint}
        </Typography>
        <Chip
          size="small"
          label={rec.responseStatus}
          color={is2xx ? 'success' : 'error'}
          variant="outlined"
        />
        <ExpandMoreIcon
          fontSize="small"
          sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: '0.15s' }}
        />
      </Box>
      {meta && (
        <Typography
          sx={{ px: 1.5, pb: 0.5, fontFamily: 'monospace', fontSize: '0.72rem', color: 'text.secondary' }}
        >
          {meta}
        </Typography>
      )}
      <Collapse in={open}>
        <Box sx={{ px: 1.5, pb: 1.5 }}>
          {rec.rawRequestBody && (
            <>
              <Typography variant="overline" color="text.secondary">
                Request body (raw)
              </Typography>
              <JsonBlock text={rec.rawRequestBody} />
            </>
          )}
          {rec.decodedPayload && (
            <>
              <Typography variant="overline" color="text.secondary">
                Decoded payload
              </Typography>
              <JsonBlock text={JSON.stringify(rec.decodedPayload, null, 2)} />
            </>
          )}
          <Typography variant="overline" color="text.secondary">
            Response ({rec.responseStatus})
          </Typography>
          <JsonBlock text={JSON.stringify(rec.responseBody, null, 2)} />
        </Box>
      </Collapse>
    </Paper>
  );
}

function JsonBlock({ text }) {
  return (
    <Box
      component="pre"
      sx={{
        fontFamily: 'monospace',
        fontSize: '0.72rem',
        bgcolor: 'grey.50',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        p: 1,
        maxHeight: 280,
        overflow: 'auto',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        m: '4px 0 8px',
      }}
    >
      {text}
    </Box>
  );
}

export default function NepalQrConsole() {
  const [qr, setQr] = useState(SAMPLE_QR);
  const [decodeStatus, setDecodeStatus] = useState(null); // {type,msg}
  const [decodeFields, setDecodeFields] = useState(null);
  const [decoding, setDecoding] = useState(false);

  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const [mobile, setMobile] = useState('');
  const [purpose, setPurpose] = useState('');
  const [remarks, setRemarks] = useState('');
  const [outcome, setOutcome] = useState('');
  const [paying, setPaying] = useState(false);
  const [payStatus, setPayStatus] = useState(null);
  const [payResult, setPayResult] = useState(null);

  const [records, setRecords] = useState([]);
  const [recordsError, setRecordsError] = useState(null);
  const [recordsLoading, setRecordsLoading] = useState(false);

  // Reference is generated on mount (client-only) to avoid SSR hydration drift.
  useEffect(() => {
    setReference(newReference());
  }, []);

  const loadRecords = useCallback(async () => {
    setRecordsLoading(true);
    setRecordsError(null);
    try {
      const res = await api(`${PROXY}/sim/nepal-qr/records`);
      if (!res.ok || !Array.isArray(res.body)) {
        setRecords([]);
        setRecordsError('Could not load records.');
      } else {
        setRecords(res.body);
      }
    } catch (e) {
      setRecords([]);
      setRecordsError('Could not reach the simulator: ' + e.message);
    } finally {
      setRecordsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRecords();
  }, [loadRecords]);

  async function decodeQr() {
    const qs = qr.trim();
    if (!qs) {
      setDecodeStatus({ type: 'error', msg: 'Paste a QR string first.' });
      return;
    }
    setDecoding(true);
    setDecodeStatus({ type: 'info', msg: 'Decoding…' });
    try {
      const res = await api(`${PROXY}/qrscan-thirdparty/parse/`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ qs }),
      });
      if (!res.ok || !res.body) {
        setDecodeFields(null);
        const detail = res.body && (res.body.detail || JSON.stringify(res.body));
        setDecodeStatus({ type: 'error', msg: 'Decode failed: ' + (detail || res.status) });
        return;
      }
      const b = res.body;
      const amountPaisa = b.trxAmount == null ? null : Math.round(Number(b.trxAmount) * 100);
      setDecodeFields({
        network: networkFromExtra(b.merchantInfoExtra),
        merchantName: b.merchantName,
        merchantId: firstMerchantId(b.merchantData),
        merchantCity: b.merchantCity,
        merchantCountry: b.merchantCountry,
        MCC: b.merchantCategoryCode,
        currency: b.trxCurrency,
        initMethod: b.initMethod,
        amountText:
          amountPaisa == null
            ? 'static (no amount)'
            : paisaToNpr(amountPaisa) + ' · ' + amountPaisa + ' paisa',
      });
      if (amountPaisa != null) setAmount((amountPaisa / 100).toFixed(2));
      setDecodeStatus({ type: 'success', msg: 'Decoded ' + (b.merchantName || 'merchant') + '.' });
    } catch (e) {
      setDecodeFields(null);
      setDecodeStatus({ type: 'error', msg: 'Could not reach the simulator: ' + e.message });
    } finally {
      setDecoding(false);
    }
  }

  async function pay() {
    const npr = Number(amount);
    if (!amount.trim() || isNaN(npr) || npr <= 0) {
      setPayStatus({ type: 'error', msg: 'Enter a valid NPR amount.' });
      return;
    }
    const amountPaisa = Math.round(npr * 100);
    let ref = reference.trim();
    if (!ref) {
      ref = newReference();
      setReference(ref);
    }
    const body = {
      qs: qr.trim() || undefined,
      amountPaisa,
      reference: ref,
      mobile: mobile.trim() || undefined,
      purpose: purpose.trim() || undefined,
      remarks: remarks.trim() || undefined,
      outcome: outcome || undefined,
    };
    setPaying(true);
    setPayStatus({ type: 'info', msg: 'Paying…' });
    try {
      const res = await api(`${PROXY}/sim/nepal-qr/ui/pay`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      setPayResult(res.body);
      if (!res.ok || !res.body || !res.body.idx) {
        const detail = res.body && (res.body.detail || JSON.stringify(res.body));
        setPayStatus({ type: 'error', msg: 'Pay failed: ' + (detail || res.status) });
        loadRecords();
        return;
      }
      const b = res.body;
      setPayStatus({ type: 'success', msg: 'Paid: ' + b.idx + ' · ' + (b.status || 'APPROVED') });
      loadRecords();
      setReference(newReference());
    } catch (e) {
      setPayResult(null);
      setPayStatus({ type: 'error', msg: 'Could not reach the simulator: ' + e.message });
    } finally {
      setPaying(false);
    }
  }

  return (
    <Box sx={{ py: 1 }}>
      <Grid container spacing={2} alignItems="flex-start">
        {/* Main column: QR + Pay */}
        <Grid item xs={12} md={7}>
          {/* QR card */}
          <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
            <Typography variant="h6" color="error.main" gutterBottom>
              1 · QR
            </Typography>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
              Paste a Nepali EMVCo QR (Fonepay / NepalPay / UnionPay / SmartQR). Sample Fonepay QR
              prefilled. Click Decode to resolve the merchant.
            </Typography>
            <TextField
              label="QR payload"
              value={qr}
              onChange={(e) => setQr(e.target.value)}
              multiline
              minRows={3}
              fullWidth
              spellCheck={false}
              inputProps={{ style: { fontFamily: 'monospace', fontSize: '0.74rem' } }}
            />
            <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
              <Button
                variant="contained"
                color="error"
                onClick={decodeQr}
                disabled={decoding}
                startIcon={decoding ? <CircularProgress size={16} color="inherit" /> : null}
              >
                Decode
              </Button>
              <Button
                variant="outlined"
                onClick={() => {
                  setQr(SAMPLE_QR);
                  setDecodeStatus({ type: 'info', msg: 'Reset to sample Fonepay QR.' });
                }}
              >
                Reset to sample
              </Button>
            </Stack>
            {decodeFields && (
              <Box sx={{ mt: 2 }}>
                <KV k="network" v={decodeFields.network} />
                <KV k="merchantName" v={decodeFields.merchantName} />
                <KV k="merchantId" v={decodeFields.merchantId} />
                <KV k="merchantCity" v={decodeFields.merchantCity} />
                <KV k="merchantCountry" v={decodeFields.merchantCountry} />
                <KV k="MCC" v={decodeFields.MCC} />
                <KV k="currency" v={decodeFields.currency} />
                <KV k="initMethod" v={decodeFields.initMethod} />
                <KV k="amount" v={decodeFields.amountText} accent />
              </Box>
            )}
            {decodeStatus && (
              <Alert severity={decodeStatus.type} sx={{ mt: 1.5 }}>
                {decodeStatus.msg}
              </Alert>
            )}
          </Paper>

          {/* Pay card */}
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="h6" color="error.main" gutterBottom>
              2 · Pay
            </Typography>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
              Runs the partner scan &amp; pay against the QR above. Amount is entered in NPR and sent
              to the partner API in paisa.
            </Typography>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={5}>
                <TextField
                  label="Amount (NPR)"
                  type="number"
                  required
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  fullWidth
                  inputProps={{ min: 0.01, step: 0.01 }}
                  placeholder="10.00"
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="Reference (unique)"
                  value={reference}
                  onChange={(e) => setReference(e.target.value)}
                  fullWidth
                />
              </Grid>
              <Grid item xs={12} sm={3}>
                <TextField
                  select
                  label="Outcome"
                  value={outcome}
                  onChange={(e) => setOutcome(e.target.value)}
                  fullWidth
                >
                  <MenuItem value="">default (approve)</MenuItem>
                  <MenuItem value="APPROVE">APPROVE</MenuItem>
                  <MenuItem value="PENDING">PENDING</MenuItem>
                  <MenuItem value="REJECT">REJECT</MenuItem>
                </TextField>
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="Mobile (optional)"
                  value={mobile}
                  onChange={(e) => setMobile(e.target.value)}
                  fullWidth
                  placeholder="9800000000"
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="Purpose (optional)"
                  value={purpose}
                  onChange={(e) => setPurpose(e.target.value)}
                  fullWidth
                  placeholder="ServicePayment"
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="Remarks (optional)"
                  value={remarks}
                  onChange={(e) => setRemarks(e.target.value)}
                  fullWidth
                  placeholder="Nepal QR pay"
                />
              </Grid>
            </Grid>
            <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
              <Button
                variant="contained"
                color="error"
                onClick={pay}
                disabled={paying}
                startIcon={paying ? <CircularProgress size={16} color="inherit" /> : null}
              >
                Pay
              </Button>
              <Button variant="outlined" onClick={() => setReference(newReference())}>
                New reference
              </Button>
            </Stack>

            {payResult && (
              <Box sx={{ mt: 2 }}>
                <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1 }}>
                  <Typography sx={{ fontFamily: 'monospace', fontWeight: 700 }}>
                    {payResult.idx || '—'}
                  </Typography>
                  <Chip
                    size="small"
                    label={payResult.status || (payResult.idx ? 'APPROVED' : 'REJECTED')}
                    color={statusColor(payResult.status || (payResult.idx ? 'APPROVED' : 'REJECTED'))}
                  />
                  {payResult.amount != null && (
                    <Typography sx={{ fontWeight: 700 }}>
                      {paisaToNpr(payResult.amount)} ({payResult.amount} paisa)
                    </Typography>
                  )}
                </Stack>
                <JsonBlock text={JSON.stringify(payResult, null, 2)} />
              </Box>
            )}
            {payStatus && (
              <Alert severity={payStatus.type} sx={{ mt: 1.5 }}>
                {payStatus.msg}
              </Alert>
            )}
          </Paper>
        </Grid>

        {/* Records column */}
        <Grid item xs={12} md={5}>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
              <Typography variant="h6" color="error.main">
                Records
              </Typography>
              <IconButton size="small" onClick={loadRecords} aria-label="Refresh records">
                {recordsLoading ? <CircularProgress size={18} /> : <RefreshIcon fontSize="small" />}
              </IconButton>
            </Stack>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
              Every request &amp; response the partner API exchanges with GMEPay+ (newest first).
              Click a row to expand.
            </Typography>
            {recordsError && (
              <Alert severity="error" sx={{ mb: 1 }}>
                {recordsError}
              </Alert>
            )}
            {!recordsError && records.length === 0 && !recordsLoading && (
              <Typography variant="body2" color="text.disabled" sx={{ textAlign: 'center', py: 2 }}>
                No records yet — decode &amp; pay to populate.
              </Typography>
            )}
            <Stack spacing={1} sx={{ maxHeight: 640, overflowY: 'auto' }}>
              {records.map((rec, i) => (
                <RecordItem key={rec.idx || rec.reference || i} rec={rec} />
              ))}
            </Stack>
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
}

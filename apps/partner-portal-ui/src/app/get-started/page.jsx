'use client';
import * as React from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import KeyIcon from '@mui/icons-material/VpnKey';
import { portalApi, currentPartnerId } from '@/api/client';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Get Started — self-serve developer onboarding.
 *
 * A logged-in partner can (1) generate a SANDBOX API key for themselves, then
 * (2) copy-paste a quickstart pre-filled with that key, and (3) skim an
 * endpoint reference — so integration can start without an engineer per
 * partner.
 *
 * The generated key's plaintext secret is shown exactly ONCE (the backend
 * stores only a hash); the page warns clearly and lets the developer copy it
 * before it disappears. Keys minted here are SANDBOX-scoped and cannot make
 * real-money production calls.
 *
 * Wire shapes:
 *   POST /v1/portal/{partnerId}/sandbox-keys {name?}
 *     -> { keyId, apiKey, prefix, scope:'SANDBOX', createdAt }   (apiKey once)
 *   GET  /v1/portal/{partnerId}/sandbox-keys
 *     -> Array<{ keyId, prefix, scope:'SANDBOX', createdAt }>    (no secret)
 */

const SANDBOX_BASE =
  process.env.NEXT_PUBLIC_SANDBOX_API_BASE || 'https://sandbox.api.gmepay.com';

// The placeholder the developer sees before they've generated a key.
const KEY_PLACEHOLDER = 'sk_test_YOUR_SANDBOX_KEY';

const ENDPOINTS = [
  { method: 'POST', path: '/v1/pay/classify', purpose: 'Classify a QR payload → country + currency' },
  { method: 'POST', path: '/v1/pay', purpose: 'Make a payment → APPROVED / DECLINED' },
  { method: 'GET', path: '/v1/portal/{partnerId}/transactions', purpose: 'List your recent transactions' },
  { method: 'GET', path: '/v1/portal/{partnerId}/transactions/{txnId}', purpose: 'Fetch one transaction detail' },
  { method: 'GET', path: '/v1/portal/{partnerId}/balance', purpose: 'Check your prefunding balance' }
];

function classifyCurl(base, key) {
  return [
    `curl -X POST ${base}/v1/pay/classify \\`,
    `  -H "X-API-Key: ${key}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '{"qrPayload":"0002012668...QR-STRING"}'`
  ].join('\n');
}

function payCurl(base, key, partner) {
  return [
    `curl -X POST ${base}/v1/pay \\`,
    `  -H "X-API-Key: ${key}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '{`,
    `    "qrPayload":"0002012668...QR-STRING",`,
    `    "amountKrw":15000,`,
    `    "currency":"KRW",`,
    `    "partner":"${partner}",`,
    `    "userRef":"order-1001"`,
    `  }'`
  ].join('\n');
}

function formatDateTime(iso) {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '—';
    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(d);
  } catch {
    return '—';
  }
}

/** A copyable code / value box with a copy-to-clipboard button. */
function CopyBox({ label, value, testId, monospace = true }) {
  const snackbar = useSnackbar();
  const onCopy = async () => {
    try {
      if (typeof navigator === 'undefined' || !navigator.clipboard) {
        throw new Error('Clipboard API not available');
      }
      await navigator.clipboard.writeText(value ?? '');
      snackbar.showSuccess(`${label ?? 'Value'} copied to clipboard`);
    } catch {
      snackbar.showError('Could not copy to clipboard');
    }
  };
  return (
    <Box
      sx={{
        position: 'relative',
        bgcolor: 'action.hover',
        borderRadius: 1,
        p: 1.5,
        pr: 5
      }}
    >
      <Box
        component="pre"
        data-testid={testId}
        sx={{
          m: 0,
          fontFamily: monospace ? 'monospace' : 'inherit',
          fontSize: '0.8rem',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all'
        }}
      >
        {value}
      </Box>
      <Tooltip title="Copy">
        <IconButton
          size="small"
          aria-label={`copy ${label ?? 'value'}`}
          data-testid={testId ? `${testId}-copy` : undefined}
          onClick={onCopy}
          sx={{ position: 'absolute', top: 6, right: 6 }}
        >
          <ContentCopyIcon fontSize="inherit" />
        </IconButton>
      </Tooltip>
    </Box>
  );
}

export default function GetStartedPage() {
  const snackbar = useSnackbar();
  const partnerId = currentPartnerId();

  const [name, setName] = React.useState('');
  const [issued, setIssued] = React.useState(null); // { keyId, apiKey, prefix, scope, createdAt }
  const [generating, setGenerating] = React.useState(false);
  const [genError, setGenError] = React.useState(null);

  const [existing, setExisting] = React.useState(null); // SandboxKeyView[] | null
  const [listError, setListError] = React.useState(null);

  const loadExisting = React.useCallback(async () => {
    if (!partnerId) return;
    setListError(null);
    try {
      const keys = await portalApi.listSandboxKeys(partnerId);
      setExisting(Array.isArray(keys) ? keys : []);
    } catch (e) {
      setListError(e?.message ?? 'Could not load your sandbox keys');
      setExisting([]);
    }
  }, [partnerId]);

  React.useEffect(() => {
    loadExisting();
  }, [loadExisting]);

  const handleGenerate = async () => {
    if (!partnerId) return;
    setGenerating(true);
    setGenError(null);
    try {
      const res = await portalApi.issueSandboxKey(partnerId, name.trim() || undefined);
      setIssued(res);
      snackbar.showSuccess('Sandbox key generated — copy it now');
      loadExisting();
    } catch (e) {
      setGenError(e?.message ?? 'Could not generate a sandbox key');
      snackbar.showError('Could not generate a sandbox key');
    } finally {
      setGenerating(false);
    }
  };

  if (!partnerId) {
    return (
      <Alert severity="warning">
        No partner id in your session. Sign in again, or ask a GMEPay+ operator to
        set the <code>partner_id</code> attribute on your Keycloak account to your
        partner code.
      </Alert>
    );
  }

  // Once a key is generated, splice its plaintext into the snippets; otherwise
  // fall back to a clearly-fake placeholder so the developer still sees the shape.
  const liveKey = issued?.apiKey || KEY_PLACEHOLDER;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">Get Started</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Generate a sandbox API key and make your first GMEPay+ call in minutes.
          Everything here runs against the <strong>sandbox</strong> environment — no
          real money moves.
        </Typography>
      </Box>

      {/* ── Step 1 — Sandbox key ─────────────────────────────────────────── */}
      <Card data-testid="step-sandbox-key">
        <CardContent>
          <Stack spacing={2}>
            <Box>
              <Typography variant="h2" sx={{ fontSize: '1.15rem' }}>
                1. Generate a sandbox key
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Sandbox keys are scoped to your partner account and can only make
                sandbox calls — they will never move real money.
              </Typography>
            </Box>

            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
              <TextField
                size="small"
                label="Key name (optional)"
                placeholder="e.g. local dev"
                value={name}
                onChange={(e) => setName(e.target.value)}
                inputProps={{ 'data-testid': 'sandbox-key-name' }}
                sx={{ maxWidth: 280 }}
              />
              <Button
                variant="contained"
                startIcon={<KeyIcon />}
                onClick={handleGenerate}
                disabled={generating}
                data-testid="generate-sandbox-key"
              >
                {generating ? 'Generating…' : 'Generate sandbox key'}
              </Button>
            </Stack>

            {genError && (
              <Alert severity="error" data-testid="sandbox-key-error">
                {genError}
              </Alert>
            )}

            {issued && (
              <Alert severity="warning" icon={false} data-testid="sandbox-key-result">
                <AlertTitle>
                  Copy your key now — it won&apos;t be shown again
                </AlertTitle>
                <Typography variant="body2" sx={{ mb: 1, color: 'text.secondary' }}>
                  We store only a hash of this secret. If you lose it, generate a
                  new one. Scope:{' '}
                  <Chip
                    label={issued.scope || 'SANDBOX'}
                    size="small"
                    color="warning"
                    variant="outlined"
                    data-testid="sandbox-key-scope"
                  />
                </Typography>
                <CopyBox
                  label="API key"
                  value={issued.apiKey}
                  testId="sandbox-key-value"
                />
              </Alert>
            )}

            {existing && existing.length > 0 && (
              <Box data-testid="existing-sandbox-keys">
                <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
                  Your existing sandbox keys
                </Typography>
                <Stack spacing={0.5}>
                  {existing.map((k) => (
                    <Stack
                      key={k.keyId}
                      direction="row"
                      spacing={1}
                      alignItems="center"
                      sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}
                    >
                      <span>{k.prefix}…</span>
                      <Chip label={k.scope || 'SANDBOX'} size="small" variant="outlined" />
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        {formatDateTime(k.createdAt)}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </Box>
            )}
            {listError && (
              <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="existing-keys-error">
                {listError}
              </Typography>
            )}
          </Stack>
        </CardContent>
      </Card>

      {/* ── Step 2 — Quickstart ──────────────────────────────────────────── */}
      <Card data-testid="step-quickstart">
        <CardContent>
          <Stack spacing={2}>
            <Box>
              <Typography variant="h2" sx={{ fontSize: '1.15rem' }}>
                2. Make your first calls
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Snippets are pre-filled with{' '}
                {issued ? 'your new key' : 'a placeholder key — generate one above to fill it in'} and
                the sandbox base URL <code>{SANDBOX_BASE}</code>.
              </Typography>
            </Box>

            <Box>
              <Typography variant="subtitle2">(a) Classify a QR</Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                Send the raw QR string; you get back the <strong>country and currency</strong> the
                QR belongs to so you know which flow to run.
              </Typography>
              <CopyBox
                label="Classify curl"
                value={classifyCurl(SANDBOX_BASE, liveKey)}
                testId="quickstart-classify"
              />
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                What you&apos;ll get back: <code>{'{ "country":"KR", "currency":"KRW", ... }'}</code>
              </Typography>
            </Box>

            <Divider />

            <Box>
              <Typography variant="subtitle2">(b) Make a payment</Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                Charge against the scanned QR. The response tells you whether the payment
                was <strong>APPROVED</strong> or <strong>DECLINED</strong>.
              </Typography>
              <CopyBox
                label="Pay curl"
                value={payCurl(SANDBOX_BASE, liveKey, partnerId)}
                testId="quickstart-pay"
              />
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                What you&apos;ll get back:{' '}
                <code>{'{ "status":"APPROVED", "txnId":"...", "schemeApprovalCode":"..." }'}</code>
              </Typography>
            </Box>
          </Stack>
        </CardContent>
      </Card>

      {/* ── Step 3 — Endpoint reference ──────────────────────────────────── */}
      <Card data-testid="step-endpoints">
        <CardContent>
          <Typography variant="h2" sx={{ fontSize: '1.15rem', mb: 1 }}>
            3. Endpoint reference
          </Typography>
          <TableContainer>
            <Table size="small" data-testid="endpoint-table">
              <TableHead>
                <TableRow>
                  <TableCell>Method</TableCell>
                  <TableCell>Path</TableCell>
                  <TableCell>Purpose</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {ENDPOINTS.map((e) => (
                  <TableRow key={`${e.method} ${e.path}`}>
                    <TableCell>
                      <Chip label={e.method} size="small" variant="outlined" />
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                      {e.path}
                    </TableCell>
                    <TableCell>{e.purpose}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>
    </Stack>
  );
}

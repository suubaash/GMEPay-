'use client';
import * as React from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Card,
  CardContent,
  Chip,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { useDispatch, useSelector } from 'react-redux';
import { fetchApiKeys } from '@/store/apiKeysSlice';
import { currentPartnerId } from '@/api/client';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import StatusChip from '@/components/StatusChip';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * API Keys page (READ-ONLY for Phase 1).
 *
 * Wire shape — GET /v1/portal/{partnerId}/api-keys returns
 *   Array<ApiKeyView>
 *     { keyId, name, prefix, scopes[], createdAt, lastUsedAt, status,
 *       environment, expiresAt }
 *
 * Source (gap register T1-3): auth-identity's real `api_keys` registry, read via
 * the BFF's RestApiKeyClient. This page previously rendered two FABRICATED keys
 * per partner (`gpk_live_<hash>` prefixes, PRIMARY/ROTATING labels, a two-scope
 * grant list and a last-used timestamp) generated from the partner id's hash,
 * because no rest client existed. Those are gone.
 *
 * Fields auth-identity genuinely does not record — shown as an em dash, never
 * back-filled with a plausible value:
 *   - `name`       : no name column on `api_keys` (V002)
 *   - `scopes`     : per-key scopes are not modelled; authorization comes from
 *                    the principal's RBAC grants, not the key
 *   - `lastUsedAt` : no `last_used_at` column, so nothing records it
 * What IS real: keyId, prefix, status (ACTIVE | PENDING_EXPIRY | REVOKED),
 * environment (SANDBOX | PRODUCTION), createdAt and expiresAt.
 *
 * The full secret is NEVER on the wire — only the prefix is exposed (and we
 * mask everything after the first 8 chars in the UI). Rotation + revocation
 * land in Phase 2 (Ops/Admin or auth-identity self-service).
 *
 * Defensive defaults: every wire field is treated as optional and defaulted
 * to a safe value so a partial backend payload doesn't crash the page.
 */

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

/**
 * Show the first 8 characters of the prefix and mask the rest with ****.
 * If the prefix is short (≤8 chars), append a static **** so the UI is
 * visually consistent.
 */
function maskPrefix(prefix) {
  const p = prefix ?? '';
  if (!p) return '—';
  if (p.length <= 8) return `${p}****`;
  return `${p.slice(0, 8)}****`;
}

async function copyToClipboard(text) {
  if (typeof navigator === 'undefined' || !navigator.clipboard) {
    throw new Error('Clipboard API not available');
  }
  await navigator.clipboard.writeText(text);
}

export default function ApiKeysPage() {
  const dispatch = useDispatch();
  const snackbar = useSnackbar();
  const partnerId = currentPartnerId();
  const { data, status, error } = useSelector((s) => s.apiKeys);

  React.useEffect(() => {
    if (partnerId && status === 'idle') dispatch(fetchApiKeys(partnerId));
  }, [partnerId, status, dispatch]);

  const retry = React.useCallback(() => {
    if (partnerId) dispatch(fetchApiKeys(partnerId));
  }, [partnerId, dispatch]);

  const handleCopyPrefix = async (prefix) => {
    try {
      await copyToClipboard(prefix ?? '');
      snackbar.showSuccess('Prefix copied to clipboard');
    } catch {
      snackbar.showError('Could not copy to clipboard');
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

  const rows = data ?? [];

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h1">API Keys</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Read-only listing of API keys provisioned for your partner account.
        </Typography>
      </Box>

      <Alert severity="info" data-testid="api-keys-phase2-banner">
        <AlertTitle>Rotation &amp; revocation coming in Phase 2</AlertTitle>
        Key rotation and revocation will be available in Phase 2 (via
        Ops/Admin or auth-identity self-service). Contact your GMEPay+
        account manager to rotate or revoke a key now.
      </Alert>

      {status === 'loading' && <LoadingSkeleton variant="table" rows={4} />}
      {status === 'failed' && (
        <ErrorAlert message={error ?? 'Failed to load API keys.'} onRetry={retry} />
      )}

      {status === 'succeeded' && (
        <Card>
          <CardContent>
            {rows.length === 0 ? (
              <EmptyState
                title="No API keys provisioned"
                message="Your partner account has no API keys yet. Contact your GMEPay+ account manager to provision one."
              />
            ) : (
              <TableContainer>
                <Table size="small" data-testid="api-keys-table">
                  <TableHead>
                    <TableRow>
                      <TableCell>Name</TableCell>
                      <TableCell>Prefix</TableCell>
                      <TableCell>Environment</TableCell>
                      <TableCell>Scopes</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Created</TableCell>
                      <TableCell>Last used</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((k) => {
                      const keyId = k?.keyId ?? k?.prefix ?? '';
                      const scopes = Array.isArray(k?.scopes) ? k.scopes : [];
                      return (
                        <TableRow key={keyId} data-testid={`api-key-row-${keyId}`}>
                          <TableCell>{k?.name ?? '—'}</TableCell>
                          <TableCell>
                            <Stack
                              direction="row"
                              spacing={0.5}
                              alignItems="center"
                              sx={{ fontFamily: 'monospace' }}
                            >
                              <span data-testid={`api-key-prefix-${keyId}`}>
                                {maskPrefix(k?.prefix)}
                              </span>
                              <Tooltip title="Copy prefix">
                                <IconButton
                                  size="small"
                                  aria-label={`copy prefix for ${k?.name ?? keyId}`}
                                  data-testid={`copy-prefix-${keyId}`}
                                  onClick={() => handleCopyPrefix(k?.prefix ?? '')}
                                >
                                  <ContentCopyIcon fontSize="inherit" />
                                </IconButton>
                              </Tooltip>
                            </Stack>
                          </TableCell>
                          <TableCell>
                            {k?.environment ? (
                              <Chip
                                label={k.environment}
                                size="small"
                                variant="outlined"
                                color={
                                  String(k.environment).toUpperCase() === 'PRODUCTION'
                                    ? 'primary'
                                    : 'default'
                                }
                                data-testid={`api-key-env-${keyId}`}
                              />
                            ) : (
                              '—'
                            )}
                          </TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                              {scopes.length === 0 ? (
                                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                                  —
                                </Typography>
                              ) : (
                                scopes.map((s) => (
                                  <Chip key={s} label={s} size="small" variant="outlined" />
                                ))
                              )}
                            </Stack>
                          </TableCell>
                          <TableCell>
                            <StatusChip status={k?.status ?? 'UNKNOWN'} />
                          </TableCell>
                          <TableCell>{formatDateTime(k?.createdAt)}</TableCell>
                          <TableCell>{formatDateTime(k?.lastUsedAt)}</TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
                <Typography
                  variant="caption"
                  sx={{ color: 'text.secondary', display: 'block', mt: 1.5 }}
                  data-testid="api-keys-absent-fields-note"
                >
                  Name, Scopes and Last used show “—” because GMEPay+ does not record them
                  against a key: keys carry no label or per-key scope (access is decided by
                  your account’s permissions), and key usage timestamps are not tracked.
                  Prefix, Environment, Status and Created are the live values from the key
                  registry.
                </Typography>
              </TableContainer>
            )}
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

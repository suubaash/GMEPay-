'use client';

import { useCallback, useEffect } from 'react';
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchPartnerCredentials,
  rotateCredential,
  clearRotateResult,
} from '@/store/partnerLifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import OneTimeCredentialModal from '../draft/[draftCode]/step-8/OneTimeCredentialModal';

/**
 * CredentialRotationPanel — lists PartnerCredentialView rows and provides a
 * per-row "Rotate" button. After a successful rotation the OneTimeCredentialModal
 * is shown with the plaintext secret (shown once only).
 *
 * PartnerCredentialView: { id, env, kind, prefix, last4, issuedAt, expiresAt, status }
 *
 * Props:
 *   partnerCode: string
 */
function credStatusColor(status) {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'EXPIRED':
    case 'REVOKED':
      return 'error';
    case 'SUPERSEDED':
      return 'default';
    default:
      return 'default';
  }
}

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString();
  } catch {
    return iso;
  }
}

export default function CredentialRotationPanel({ partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const { credentials, credentialsLoading, credentialsError, rotatingId, rotateResult } =
    useAppSelector((s) => s.partnerLifecycle);

  const load = useCallback(() => {
    if (partnerCode) dispatch(fetchPartnerCredentials(partnerCode));
  }, [dispatch, partnerCode]);

  useEffect(() => {
    load();
  }, [load]);

  const handleRotate = async (credentialId) => {
    try {
      await dispatch(rotateCredential({ partnerCode, credentialId })).unwrap();
      // OneTimeCredentialModal will open via rotateResult selector
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      snackbar.error(`Rotation failed: ${msg}`);
    }
  };

  const handleModalClose = () => {
    dispatch(clearRotateResult());
    load(); // refresh list to show new ACTIVE credential
  };

  if (credentialsLoading && credentials.length === 0) {
    return <LoadingSkeleton variant="table" rows={3} />;
  }

  return (
    <Box data-testid="credential-rotation-panel">
      <ErrorAlert message={credentialsError} onRetry={load} />

      {credentials.length === 0 && !credentialsLoading && (
        <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
          No credentials found for this partner.
        </Typography>
      )}

      {credentials.length > 0 && (
        <Table size="small" aria-label="Partner credentials">
          <TableHead>
            <TableRow>
              <TableCell>Environment</TableCell>
              <TableCell>Kind</TableCell>
              <TableCell>Prefix</TableCell>
              <TableCell>Last 4</TableCell>
              <TableCell>Issued</TableCell>
              <TableCell>Expires</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Action</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {credentials.map((cred) => (
              <TableRow key={cred.id} data-testid={`cred-row-${cred.id}`}>
                <TableCell>{cred.env ?? '—'}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>
                  {cred.kind ?? '—'}
                </TableCell>
                <TableCell sx={{ fontFamily: 'monospace' }}>{cred.prefix ?? '—'}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace' }}>{cred.last4 ?? '—'}</TableCell>
                <TableCell>{formatDate(cred.issuedAt)}</TableCell>
                <TableCell>{formatDate(cred.expiresAt)}</TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    label={cred.status ?? '—'}
                    color={credStatusColor(cred.status)}
                  />
                </TableCell>
                <TableCell align="right">
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={
                      rotatingId === cred.id ? (
                        <CircularProgress size={14} />
                      ) : (
                        <RefreshIcon fontSize="small" />
                      )
                    }
                    disabled={rotatingId !== null || cred.status === 'REVOKED'}
                    onClick={() => handleRotate(cred.id)}
                    data-testid={`rotate-btn-${cred.id}`}
                  >
                    Rotate
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {/* One-time credential modal — shown after successful rotation */}
      <OneTimeCredentialModal
        open={rotateResult !== null}
        credential={rotateResult}
        onClose={handleModalClose}
      />
    </Box>
  );
}

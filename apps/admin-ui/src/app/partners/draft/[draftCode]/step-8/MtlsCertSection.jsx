'use client';

import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep8MtlsCert } from '@/store/lifecycleSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * MtlsCertSection — paste a PEM certificate for mTLS client auth, then
 * upload it. After upload the server replies with parsed certificate metadata
 * (subject DN, issuer DN, validity, fingerprint) which is displayed below.
 *
 * Saves via PATCH /v1/admin/partners/draft/{code}/step-8/mtls-cert.
 *
 * @param {object}   props
 * @param {string}   props.partnerCode  URL-pinned identifier.
 */
export function MtlsCertSection({ partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const saving = useAppSelector((s) => s.lifecycle?.saving ?? false);

  const [pem, setPem] = useState('');
  /** MtlsCertParsedView returned by the server after upload. */
  const [parsed, setParsed] = useState(null);
  const [uploadError, setUploadError] = useState(null);

  const handleUpload = async () => {
    if (!partnerCode) {
      snackbar.error('No partner code — cannot upload.');
      return;
    }
    const trimmed = pem.trim();
    if (!trimmed) {
      snackbar.error('Paste a PEM certificate before uploading.');
      return;
    }
    setUploadError(null);
    try {
      const result = await dispatch(
        patchStep8MtlsCert({ partnerCode, body: { pemCertificate: trimmed } }),
      ).unwrap();
      setParsed(result);
      snackbar.success('Certificate uploaded and parsed.');
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      setUploadError(message);
      snackbar.error(`Upload failed: ${message}`);
    }
  };

  return (
    <Box aria-label="mtls-cert-section">
      <Typography variant="h6" gutterBottom>
        mTLS Client Certificate
      </Typography>

      <Stack spacing={2}>
        <TextField
          label="PEM certificate"
          value={pem}
          onChange={(e) => setPem(e.target.value)}
          multiline
          minRows={6}
          fullWidth
          placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
          inputProps={{ 'aria-label': 'pem-textarea', style: { fontFamily: 'monospace', fontSize: '0.8rem' } }}
        />

        {uploadError && (
          <Alert severity="error" aria-label="mtls-upload-error">
            {uploadError}
          </Alert>
        )}

        <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            variant="contained"
            onClick={handleUpload}
            disabled={saving || !pem.trim()}
            startIcon={saving ? <CircularProgress size={16} color="inherit" /> : null}
            aria-label="upload-mtls-cert"
          >
            Upload certificate
          </Button>
        </Box>

        {/* Parsed certificate metadata */}
        {parsed && (
          <>
            <Divider />
            <Box aria-label="parsed-cert-info">
              <Typography variant="subtitle2" gutterBottom>
                Parsed certificate
              </Typography>
              <Stack spacing={0.5}>
                {[
                  { label: 'Subject DN', value: parsed.subjectDn, id: 'parsed-subject-dn' },
                  { label: 'Issuer DN', value: parsed.issuerDn, id: 'parsed-issuer-dn' },
                  { label: 'Not before', value: parsed.notBefore, id: 'parsed-not-before' },
                  { label: 'Not after', value: parsed.notAfter, id: 'parsed-not-after' },
                  { label: 'SHA-256 fingerprint', value: parsed.fingerprint, id: 'parsed-fingerprint' },
                ].map(({ label, value, id }) => (
                  <Box key={id} sx={{ display: 'flex', gap: 2 }}>
                    <Typography
                      variant="body2"
                      color="text.secondary"
                      sx={{ minWidth: 180, flexShrink: 0 }}
                    >
                      {label}
                    </Typography>
                    <Typography
                      variant="body2"
                      fontFamily="monospace"
                      aria-label={id}
                    >
                      {value ?? '—'}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Box>
          </>
        )}
      </Stack>
    </Box>
  );
}

export default MtlsCertSection;

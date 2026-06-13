'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Alert, Box, Button, CircularProgress, Stack, TextField, Typography } from '@mui/material';
import { useAppDispatch } from '@/store';
import { createDraft } from '@/store/draftsSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Legacy {@code /partners/new} route — superseded by the 8-step Partner Setup
 * wizard per the Slice 1 plan exit gate.
 *
 * <p>The old direct-POST 4-field form is gone (it did not satisfy any of the
 * regulatory/data-model requirements documented in PRD-07, DAT-03, or the
 * Business Scenarios doc — see the coverage audit). This route now collects
 * only the {@code partnerCode}, creates a draft via the BFF, and routes the
 * operator to {@code /partners/draft/<code>/step-1} where they continue
 * through Identity → Contacts → KYB → Banking → Prefunding → Commercial →
 * Schemes → Activate.
 *
 * <p>Existing bookmarks on {@code /partners/new} therefore keep working but
 * now land on the wizard entry point, not the deprecated form.
 */
export default function NewPartnerPage() {
  const router = useRouter();
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const [partnerCode, setPartnerCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  // Eager redirect when the operator types nothing and just navigates away —
  // we don't want them stranded on a half-page. The hook intentionally only
  // pre-warms; the actual route change waits for them to enter a code.
  useEffect(() => {
    setError(null);
  }, [partnerCode]);

  const trimmed = partnerCode.trim().toUpperCase();
  const formatOk = /^[A-Z0-9_-]{3,20}$/.test(trimmed);

  const handleStart = async () => {
    if (!formatOk || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const draft = await dispatch(createDraft({ partnerCode: trimmed })).unwrap();
      router.replace(`/partners/draft/${encodeURIComponent(draft.partnerCode)}/step-1`);
    } catch (e) {
      const message = e?.message || (typeof e === 'string' ? e : 'Failed to start draft');
      setError(message);
      snackbar.error(`Could not start draft: ${message}`);
      setSubmitting(false);
    }
  };

  return (
    <Box sx={{ maxWidth: 640 }}>
      <Typography variant="h1" gutterBottom>
        New partner
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Choose a partner code to begin. After this you&apos;ll work through the
        8-step setup wizard (Identity, Contacts, KYB, Banking, Prefunding,
        Commercial terms, Schemes, Activate). The draft is saved on every
        step so you can close the browser and resume by URL.
      </Typography>

      <Stack spacing={2}>
        <TextField
          label="Partner code"
          fullWidth
          required
          autoFocus
          value={partnerCode}
          onChange={(e) => setPartnerCode(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleStart();
          }}
          inputProps={{ 'aria-label': 'Partner code', maxLength: 20, style: { textTransform: 'uppercase' } }}
          error={!!partnerCode && !formatOk}
          helperText={
            !partnerCode || formatOk
              ? '3-20 uppercase letters, digits, hyphen or underscore (e.g. GME_KR_001)'
              : 'Must be 3-20 uppercase A-Z, 0-9, hyphen or underscore'
          }
        />

        {error && <Alert severity="error">{error}</Alert>}

        <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
          <Button onClick={() => router.push('/partners')}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!formatOk || submitting}
            onClick={handleStart}
            startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            Start wizard
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

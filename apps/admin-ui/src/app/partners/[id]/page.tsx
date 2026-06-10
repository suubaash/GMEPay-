'use client';

import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Snackbar,
  Stack,
  Typography,
} from '@mui/material';
import { useParams } from 'next/navigation';
import RoundingModeSelect from '@/components/RoundingModeSelect';
import { adminApi } from '@/api/client';
import type { PartnerDetail, RoundingMode } from '@/api/types';

/**
 * Partner detail page.
 *
 * Read-only fields (partnerId, type, settlementCurrency) plus an editable
 * settlementRoundingMode. Saving PUTs /v1/admin/partners/{id}/rounding-mode
 * which is audit-logged in config-registry.
 */
export default function PartnerDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;

  const [partner, setPartner] = useState<PartnerDetail | null>(null);
  const [mode, setMode] = useState<RoundingMode>('HALF_UP');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [snack, setSnack] = useState<{ open: boolean; msg: string; severity: 'success' | 'error' }>(
    { open: false, msg: '', severity: 'success' },
  );

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    adminApi
      .getPartner(id)
      .then((p) => {
        if (cancelled) return;
        setPartner(p);
        setMode(p.settlementRoundingMode);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setLoadError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const onSave = async () => {
    if (!id) return;
    setSaving(true);
    try {
      const updated = await adminApi.updatePartnerRoundingMode(id, mode);
      setPartner(updated);
      setSnack({ open: true, msg: `Rounding mode set to ${updated.settlementRoundingMode}`, severity: 'success' });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      setSnack({ open: true, msg: `Update failed: ${message}`, severity: 'error' });
    } finally {
      setSaving(false);
    }
  };

  if (loadError) {
    return <Alert severity="error">{loadError}</Alert>;
  }
  if (!partner) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        {partner.partnerId}
      </Typography>
      <Card sx={{ maxWidth: 640 }}>
        <CardContent>
          <Stack spacing={2}>
            <Box>
              <Typography variant="body2" color="text.secondary">
                Partner type
              </Typography>
              <Typography>{partner.type}</Typography>
            </Box>
            <Box>
              <Typography variant="body2" color="text.secondary">
                Settlement currency
              </Typography>
              <Typography>{partner.settlementCurrency}</Typography>
            </Box>
            <RoundingModeSelect
              value={mode}
              onChange={setMode}
              helperText="Changing this affects ONLY transactions created after the change."
            />
            <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
              <Button
                variant="contained"
                onClick={onSave}
                disabled={saving || mode === partner.settlementRoundingMode}
              >
                Save rounding mode
              </Button>
            </Box>
          </Stack>
        </CardContent>
      </Card>

      <Snackbar
        open={snack.open}
        autoHideDuration={5000}
        onClose={() => setSnack((s) => ({ ...s, open: false }))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      >
        <Alert
          severity={snack.severity}
          onClose={() => setSnack((s) => ({ ...s, open: false }))}
        >
          {snack.msg}
        </Alert>
      </Snackbar>
    </Box>
  );
}

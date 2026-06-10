'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import EditIcon from '@mui/icons-material/Edit';
import { useParams } from 'next/navigation';
import RoundingModeSelect from '@/components/RoundingModeSelect';
import ErrorAlert from '@/components/ErrorAlert';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';
import { useAppDispatch, useAppSelector } from '@/store';
import { getPartner, updatePartnerRoundingMode } from '@/store/partnersSlice';
import type { RoundingMode } from '@/api/types';

/**
 * Partner detail page.
 *
 * Read-only fields (partnerId, type, settlementCurrency, audit timestamps)
 * plus an inline "Edit rounding mode" dialog that PUTs
 * /v1/admin/partners/{id}/rounding-mode — which is audit-logged in
 * config-registry and rate-locked onto future transactions
 * (docs/MONEY_CONVENTION.md).
 */
export default function PartnerDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { details, detailLoading, saving, error } = useAppSelector((s) => s.partners);

  const partner = id ? details[id] : undefined;

  const reload = useCallback(() => {
    if (id) dispatch(getPartner(id));
  }, [dispatch, id]);

  useEffect(() => {
    reload();
  }, [reload]);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [draftMode, setDraftMode] = useState<RoundingMode>('HALF_UP');

  const openDialog = () => {
    if (!partner) return;
    setDraftMode(partner.settlementRoundingMode);
    setDialogOpen(true);
  };

  const onSave = async () => {
    if (!id || !partner) return;
    try {
      const updated = await dispatch(
        updatePartnerRoundingMode({ id, mode: draftMode }),
      ).unwrap();
      snackbar.success(`Rounding mode set to ${updated.settlementRoundingMode}`);
      setDialogOpen(false);
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      snackbar.error(`Update failed: ${message}`);
    }
  };

  if (error && !partner) {
    return <ErrorAlert message={error} onRetry={reload} title="Could not load partner" />;
  }
  if (!partner) {
    return <LoadingSkeleton variant="page" />;
  }

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        {partner.partnerId}
      </Typography>
      <ErrorAlert message={error} onRetry={reload} />
      <Card sx={{ maxWidth: 720 }}>
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
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="body2" color="text.secondary">
                  Settlement rounding mode
                </Typography>
                <Typography>{partner.settlementRoundingMode}</Typography>
              </Box>
              <Button
                variant="outlined"
                startIcon={<EditIcon />}
                onClick={openDialog}
                disabled={detailLoading}
              >
                Edit
              </Button>
            </Box>
            {partner.createdAt ? (
              <Box>
                <Typography variant="body2" color="text.secondary">
                  Created at
                </Typography>
                <Typography>{partner.createdAt}</Typography>
              </Box>
            ) : null}
            {partner.updatedAt ? (
              <Box>
                <Typography variant="body2" color="text.secondary">
                  Updated at
                </Typography>
                <Typography>{partner.updatedAt}</Typography>
              </Box>
            ) : null}
          </Stack>
        </CardContent>
      </Card>

      <Dialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        aria-labelledby="edit-rounding-title"
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle id="edit-rounding-title">Edit rounding mode</DialogTitle>
        <DialogContent>
          <Box sx={{ mt: 1 }}>
            <RoundingModeSelect
              value={draftMode}
              onChange={setDraftMode}
              helperText="Changing this affects ONLY transactions created after the change. Audit-logged in config-registry."
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={onSave}
            variant="contained"
            disabled={saving || draftMode === partner.settlementRoundingMode}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

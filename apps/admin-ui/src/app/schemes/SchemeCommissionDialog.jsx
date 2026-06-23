'use client';

import { useEffect, useMemo } from 'react';
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  FormHelperText,
  Grid,
  IconButton,
  InputAdornment,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { useAppDispatch, useAppSelector } from '@/store';
import { useSnackbar } from '@/components/SnackbarProvider';
import {
  fetchSchemeCommissionShares,
  saveSchemeCommissionShares,
} from '@/store/schemeCommissionSlice';
import {
  schemeCommissionFormSchema,
  DIRECTION_OPTIONS,
  fmtPct,
  firstDuplicateIndex,
} from '@/schemas/commissionShareSchema';

/**
 * Scheme-side Commission Sharing editor (V031) — the configurable GME↔scheme
 * split of the net merchant fee, per direction. There is NO fixed 70/30.
 * Launched per-scheme from the QR Schemes table; saves via a bulk-replace PUT.
 *
 * @param {object}   props
 * @param {boolean}  props.open
 * @param {string}   props.schemeId   Scheme code being edited (e.g. "ZEROPAY").
 * @param {Function} props.onClose
 */
export default function SchemeCommissionDialog({ open, schemeId, onClose }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const saving = useAppSelector((s) => s.schemeCommission?.saving ?? false);
  const loading = useAppSelector(
    (s) => s.schemeCommission?.loadingByScheme?.[schemeId] ?? false,
  );
  const savedShares = useAppSelector(
    (s) => (schemeId ? s.schemeCommission?.byScheme?.[schemeId] ?? null : null),
  );

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(schemeCommissionFormSchema),
    defaultValues: { shares: [] },
    mode: 'onBlur',
  });

  const { fields, append, remove } = useFieldArray({ control, name: 'shares' });

  // Fetch the current set whenever the dialog opens for a scheme.
  useEffect(() => {
    if (open && schemeId) {
      dispatch(fetchSchemeCommissionShares(schemeId)).catch(() => {});
    }
  }, [open, schemeId, dispatch]);

  // While open, the form always mirrors the current set for THIS scheme:
  // empty until the GET resolves (never another scheme's rows), repopulated
  // when it arrives. The dialog is also keyed by scheme in the parent, so a
  // fresh instance mounts per row — this is belt-and-suspenders against bleed.
  useEffect(() => {
    if (!open) return;
    const rows = Array.isArray(savedShares) ? savedShares : [];
    reset({
      shares: rows.map((r) => ({
        direction: r.direction ?? '',
        gmeSharePct: r.gmeSharePct != null ? String(r.gmeSharePct) : '0.7000',
        vanFeePct: r.vanFeePct != null ? String(r.vanFeePct) : '0.0000',
      })),
    });
  }, [open, savedShares, reset]);

  const watched = useWatch({ control, name: 'shares' });
  const dupIndex = useMemo(() => firstDuplicateIndex(watched ?? [], false), [watched]);

  const onSubmit = async (values) => {
    if (!schemeId) return;
    if (dupIndex >= 0) {
      snackbar.error(`Row ${dupIndex + 1} duplicates an earlier direction — each may appear once.`);
      return;
    }
    const body = (values.shares ?? []).map((r) => ({
      direction: (r.direction ?? '').trim() || null,
      gmeSharePct: (r.gmeSharePct ?? '').trim(),
      vanFeePct: (r.vanFeePct ?? '').trim() || '0',
    }));
    try {
      await dispatch(saveSchemeCommissionShares({ schemeId, shares: body })).unwrap();
      snackbar.success(
        body.length === 0
          ? `Commission shares cleared for ${schemeId}.`
          : `Saved ${body.length} commission row(s) for ${schemeId}.`,
      );
      onClose?.();
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  const busy = saving || isSubmitting || loading;

  return (
    <Dialog open={!!open} onClose={onClose} maxWidth="md" fullWidth aria-label="scheme-commission-dialog">
      <DialogTitle>Commission sharing — {schemeId}</DialogTitle>
      <DialogContent dividers>
        <Box component="form" id="scheme-commission-form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={3}>
            <Typography variant="body2" color="text.secondary">
              GME&#x2019;s share of the net merchant fee, per direction. Decimal fraction
              (e.g. <code>0.7000</code> = 70%); the scheme operator keeps the remainder.
              VAN rate is deducted from the gross fee before the split. Leave direction
              blank to apply to all. There is no fixed share.
            </Typography>

            <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
              <Button
                size="small"
                variant="outlined"
                startIcon={<AddIcon />}
                onClick={() => append({ direction: '', gmeSharePct: '0.7000', vanFeePct: '0.0008' })}
                aria-label="add-scheme-commission-row"
              >
                Add row
              </Button>
            </Box>

            {fields.length === 0 && (
              <Alert severity="info" variant="outlined">
                No commission shares configured for this scheme yet.
              </Alert>
            )}

            <Stack spacing={3} divider={<Divider flexItem />}>
              {fields.map((field, index) => {
                const rowErrors = errors?.shares?.[index];
                const gmePct = fmtPct(watched?.[index]?.gmeSharePct);
                const gmeNum = parseFloat(watched?.[index]?.gmeSharePct ?? '');
                const schemePct = Number.isFinite(gmeNum) ? `${((1 - gmeNum) * 100).toFixed(2)}%` : '';
                return (
                  <Box key={field.id} aria-label={`scheme-commission-row-${index}`}>
                    <Stack spacing={2}>
                      <Stack direction="row" justifyContent="space-between" alignItems="center">
                        <Typography variant="subtitle2" color="text.secondary">
                          Row {index + 1}
                        </Typography>
                        <IconButton
                          size="small"
                          onClick={() => remove(index)}
                          aria-label={`remove-scheme-commission-row-${index}`}
                          color="error"
                        >
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </Stack>

                      <Grid container spacing={2}>
                        <Grid item xs={12} md={4}>
                          <Controller
                            name={`shares.${index}.direction`}
                            control={control}
                            render={({ field: f }) => (
                              <FormControl fullWidth size="small" error={!!rowErrors?.direction}>
                                <InputLabel id={`scs-dir-${index}`}>Direction</InputLabel>
                                <Select
                                  {...f}
                                  value={f.value ?? ''}
                                  labelId={`scs-dir-${index}`}
                                  label="Direction"
                                  inputProps={{ 'aria-label': `shares.${index}.direction` }}
                                >
                                  {DIRECTION_OPTIONS.map((o) => (
                                    <MenuItem key={o.value || 'ALL'} value={o.value}>
                                      {o.label}
                                    </MenuItem>
                                  ))}
                                </Select>
                                <FormHelperText>
                                  {rowErrors?.direction?.message ?? 'Blank = all directions'}
                                </FormHelperText>
                              </FormControl>
                            )}
                          />
                        </Grid>
                        <Grid item xs={12} md={4}>
                          <TextField
                            label="GME share"
                            fullWidth
                            size="small"
                            required
                            {...register(`shares.${index}.gmeSharePct`)}
                            error={!!rowErrors?.gmeSharePct}
                            helperText={rowErrors?.gmeSharePct?.message ?? 'Fraction in (0,1], e.g. 0.7000'}
                            InputProps={{
                              endAdornment: (
                                <InputAdornment position="end">
                                  <Tooltip title="GME's share of the net merchant fee. 0.7000 = 70%. Scheme keeps the rest.">
                                    <InfoOutlinedIcon fontSize="small" color="action" />
                                  </Tooltip>
                                </InputAdornment>
                              ),
                            }}
                            inputProps={{ 'aria-label': `shares.${index}.gmeSharePct` }}
                          />
                        </Grid>
                        <Grid item xs={12} md={4}>
                          <TextField
                            label="VAN rate"
                            fullWidth
                            size="small"
                            {...register(`shares.${index}.vanFeePct`)}
                            error={!!rowErrors?.vanFeePct}
                            helperText={rowErrors?.vanFeePct?.message ?? 'Deducted before split (e.g. 0.0008)'}
                            inputProps={{ 'aria-label': `shares.${index}.vanFeePct` }}
                          />
                        </Grid>
                      </Grid>

                      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                        <Typography variant="body2" color="text.secondary">
                          Split:
                        </Typography>
                        <Chip label={`GME ${gmePct || '—'}`} size="small" color="primary" variant="outlined" />
                        <Chip label={`Scheme ${schemePct || '—'}`} size="small" variant="outlined" />
                      </Stack>
                    </Stack>
                  </Box>
                );
              })}
            </Stack>

            {dupIndex >= 0 && (
              <Alert severity="error">
                Row {dupIndex + 1} duplicates an earlier direction. Each direction may appear once.
              </Alert>
            )}
          </Stack>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy} aria-label="cancel-scheme-commission">
          Cancel
        </Button>
        <Button
          type="submit"
          form="scheme-commission-form"
          variant="contained"
          disabled={busy || dupIndex >= 0}
          startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
          aria-label="save-scheme-commission"
        >
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

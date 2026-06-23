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
import SaveIcon from '@mui/icons-material/Save';
import { useAppDispatch, useAppSelector } from '@/store';
import { useSnackbar } from '@/components/SnackbarProvider';
import {
  fetchPartnerCommissionShares,
  savePartnerCommissionShares,
} from '@/store/commissionSharesSlice';
import {
  partnerCommissionFormSchema,
  DIRECTION_OPTIONS,
  fmtPct,
  firstDuplicateIndex,
} from '@/schemas/commissionShareSchema';

/**
 * Partner-side Commission Sharing section for Step 6 (Commercial Terms).
 *
 * Configures the GME↔partner split of GME's commission (V031) — the partner's
 * share of GME's net cut, per (scheme × direction). There is NO fixed share.
 *
 * Self-contained: owns its own RHF instance, loads the current set via the
 * commissionShares slice, and saves via a dedicated PUT (bulk replace) on its
 * own button — independent of the composite step-6 "Save & next". This mirrors
 * the standalone {@code PUT /v1/admin/partners/{code}/commission-shares}
 * endpoint, which is allowed in any partner lifecycle state.
 *
 * @param {object} props
 * @param {string} props.partnerCode  URL-pinned partner identifier.
 */
export default function CommissionShareSection({ partnerCode }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const saving = useAppSelector((s) => s.commissionShares?.saving ?? false);
  const loading = useAppSelector(
    (s) => s.commissionShares?.loadingByCode?.[partnerCode] ?? false,
  );
  const savedShares = useAppSelector(
    (s) => s.commissionShares?.sharesByCode?.[partnerCode] ?? null,
  );

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(partnerCommissionFormSchema),
    defaultValues: { shares: [] },
    mode: 'onBlur',
  });

  const { fields, append, remove } = useFieldArray({ control, name: 'shares' });

  // Load current shares on mount.
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchPartnerCommissionShares(partnerCode)).catch(() => {});
    }
  }, [partnerCode, dispatch]);

  // Re-populate the editor when the saved set arrives from the BFF.
  useEffect(() => {
    if (Array.isArray(savedShares)) {
      reset({
        shares: savedShares.map((r) => ({
          schemeId: r.schemeId ?? '',
          direction: r.direction ?? '',
          partnerSharePct: r.partnerSharePct != null ? String(r.partnerSharePct) : '0.0000',
        })),
      });
    }
  }, [savedShares, reset]);

  const watched = useWatch({ control, name: 'shares' });
  const dupIndex = useMemo(() => firstDuplicateIndex(watched ?? [], true), [watched]);

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    if (dupIndex >= 0) {
      snackbar.error(
        `Row ${dupIndex + 1} duplicates an earlier (scheme, direction) pair — each pair may appear once.`,
      );
      return;
    }
    const body = (values.shares ?? []).map((r) => ({
      schemeId: (r.schemeId ?? '').trim() || null,
      direction: (r.direction ?? '').trim() || null,
      partnerSharePct: (r.partnerSharePct ?? '').trim(),
    }));
    try {
      await dispatch(savePartnerCommissionShares({ partnerCode, shares: body })).unwrap();
      snackbar.success(
        body.length === 0
          ? 'Commission shares cleared.'
          : `Saved ${body.length} commission share row(s).`,
      );
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Commission shares save failed: ${message}`);
    }
  };

  const busy = saving || isSubmitting || loading;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="commission-share-section"
    >
      <Stack spacing={3}>
        <Box>
          <Stack
            direction="row"
            justifyContent="space-between"
            alignItems="flex-start"
            flexWrap="wrap"
            gap={1}
          >
            <Box>
              <Typography variant="h6" gutterBottom>
                Commission Sharing (partner)
              </Typography>
              <Typography variant="body2" color="text.secondary">
                The partner&#x2019;s share of GME&#x2019;s commission, per scheme and
                direction. Decimal fraction (e.g. <code>0.3000</code> = 30%); GME keeps the
                remainder. Leave scheme/direction blank to apply to all. Saved
                independently of the rest of Step&nbsp;6.
              </Typography>
            </Box>
            <Button
              size="small"
              variant="outlined"
              startIcon={<AddIcon />}
              onClick={() =>
                append({ schemeId: '', direction: '', partnerSharePct: '0.3000' })
              }
              aria-label="add-commission-row"
            >
              Add row
            </Button>
          </Stack>
        </Box>

        {fields.length === 0 && (
          <Alert severity="info" variant="outlined" aria-label="commission-empty-hint">
            No commission shares configured — GME retains 100% of its commission for
            this partner. Click <strong>Add row</strong> to define a split.
          </Alert>
        )}

        <Stack spacing={3} divider={<Divider flexItem />}>
          {fields.map((field, index) => {
            const rowErrors = errors?.shares?.[index];
            const pct = fmtPct(watched?.[index]?.partnerSharePct);
            return (
              <Box key={field.id} aria-label={`commission-row-${index}`}>
                <Stack spacing={2}>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="subtitle2" color="text.secondary">
                      Share {index + 1}
                    </Typography>
                    <IconButton
                      size="small"
                      onClick={() => remove(index)}
                      aria-label={`remove-commission-row-${index}`}
                      color="error"
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Stack>

                  <Grid container spacing={2}>
                    <Grid item xs={12} md={4}>
                      <TextField
                        label="Scheme (optional)"
                        fullWidth
                        size="small"
                        {...register(`shares.${index}.schemeId`)}
                        error={!!rowErrors?.schemeId}
                        helperText={rowErrors?.schemeId?.message ?? 'Blank = all schemes'}
                        inputProps={{ 'aria-label': `shares.${index}.schemeId` }}
                      />
                    </Grid>
                    <Grid item xs={12} md={4}>
                      <Controller
                        name={`shares.${index}.direction`}
                        control={control}
                        render={({ field: f }) => (
                          <FormControl fullWidth size="small" error={!!rowErrors?.direction}>
                            <InputLabel id={`pcs-dir-${index}`}>Direction</InputLabel>
                            <Select
                              {...f}
                              value={f.value ?? ''}
                              labelId={`pcs-dir-${index}`}
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
                        label="Partner share"
                        fullWidth
                        size="small"
                        required
                        {...register(`shares.${index}.partnerSharePct`)}
                        error={!!rowErrors?.partnerSharePct}
                        helperText={
                          rowErrors?.partnerSharePct?.message ??
                          (pct ? `Partner keeps ${pct} of GME's commission` : 'Fraction, e.g. 0.3000')
                        }
                        InputProps={{
                          endAdornment: (
                            <InputAdornment position="end">
                              <Tooltip title="Decimal fraction in [0,1]. 0.3000 = 30%. GME keeps the remainder.">
                                <InfoOutlinedIcon fontSize="small" color="action" />
                              </Tooltip>
                            </InputAdornment>
                          ),
                        }}
                        inputProps={{ 'aria-label': `shares.${index}.partnerSharePct` }}
                      />
                    </Grid>
                  </Grid>

                  <Stack direction="row" spacing={1} alignItems="center">
                    <Typography variant="body2" color="text.secondary">
                      Partner share:
                    </Typography>
                    <Chip
                      label={pct || '—'}
                      size="small"
                      aria-label={`shares.${index}.pct-preview`}
                    />
                  </Stack>
                </Stack>
              </Box>
            );
          })}
        </Stack>

        {dupIndex >= 0 && (
          <Alert severity="error" aria-label="commission-duplicate-blocked">
            Row {dupIndex + 1} duplicates an earlier (scheme, direction) pair. Each pair
            may appear at most once.
          </Alert>
        )}

        <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button
            type="submit"
            variant="contained"
            disabled={busy || dupIndex >= 0}
            startIcon={
              busy ? <CircularProgress size={16} color="inherit" /> : <SaveIcon />
            }
            aria-label="save-commission-shares"
          >
            Save commission shares
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

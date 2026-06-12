'use client';

import { useEffect } from 'react';
import { Controller, useFieldArray } from 'react-hook-form';
import {
  Box,
  Button,
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
import { DIRECTIONS } from '@/schemas/partnerStep6CommercialSchema';

/**
 * Fee Schedule section for Step 6 (Commercial Terms).
 *
 * Renders:
 *   - Scheme text input + Direction select (INBOUND / OUTBOUND)
 *   - Fixed fee (USD) and BPS fee decimal-string inputs
 *   - Optional tier table: multi-row fromVolumeUsd + bpsOverride
 *
 * Props are driven entirely by react-hook-form via the parent page's
 * useForm instance (control, register, errors, fields are namespaced
 * under "feeSchedule.*").
 *
 * @param {object}   props
 * @param {object}   props.control     RHF control from the parent form.
 * @param {Function} props.register    RHF register from the parent form.
 * @param {object}   props.errors      RHF errors scoped to feeSchedule.
 */
export default function FeeScheduleSection({ control, register, errors }) {
  const { fields, append, remove } = useFieldArray({
    control,
    name: 'feeSchedule.tiers',
  });

  return (
    <Box aria-label="fee-schedule-section">
      <Stack spacing={3}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Fee Schedule
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Define the per-scheme fee structure. Fixed fee and BPS fee are
            applied additively. Tiers override the BPS fee for volumes above
            the tier threshold.
          </Typography>
        </Box>

        {/* Scheme + Direction */}
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <TextField
              label="Scheme"
              fullWidth
              required
              {...register('feeSchedule.scheme')}
              error={!!errors?.scheme}
              helperText={
                errors?.scheme?.message ?? 'Payment scheme (e.g. ZEROPAY, VISA)'
              }
              inputProps={{ 'aria-label': 'feeSchedule.scheme' }}
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <Controller
              name="feeSchedule.direction"
              control={control}
              render={({ field }) => (
                <FormControl
                  fullWidth
                  required
                  error={!!errors?.direction}
                >
                  <InputLabel id="fee-direction-label">Direction</InputLabel>
                  <Select
                    {...field}
                    value={field.value ?? ''}
                    labelId="fee-direction-label"
                    label="Direction"
                    inputProps={{ 'aria-label': 'feeSchedule.direction' }}
                  >
                    {DIRECTIONS.map((d) => (
                      <MenuItem key={d} value={d}>
                        {d}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors?.direction?.message ?? 'Transaction direction'}
                  </FormHelperText>
                </FormControl>
              )}
            />
          </Grid>
        </Grid>

        {/* Fixed fee + BPS fee */}
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <TextField
              label="Fixed fee (USD)"
              fullWidth
              required
              {...register('feeSchedule.fixedFeeUsd')}
              error={!!errors?.fixedFeeUsd}
              helperText={
                errors?.fixedFeeUsd?.message ?? 'Flat fee per transaction (decimal string)'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'feeSchedule.fixedFeeUsd' }}
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <TextField
              label="BPS fee"
              fullWidth
              required
              {...register('feeSchedule.bpsFee')}
              error={!!errors?.bpsFee}
              helperText={
                errors?.bpsFee?.message ?? 'Basis points on transaction amount (e.g. "150" = 1.5%)'
              }
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <Tooltip title="1 BPS = 0.01%. Enter as a plain number, e.g. 150 for 1.5%.">
                      <InfoOutlinedIcon fontSize="small" color="action" />
                    </Tooltip>
                  </InputAdornment>
                ),
              }}
              inputProps={{ 'aria-label': 'feeSchedule.bpsFee' }}
            />
          </Grid>
        </Grid>

        <Divider />

        {/* Tier table */}
        <Box>
          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                Volume tiers (optional)
              </Typography>
              <Tooltip title="Each tier applies the bpsOverride for monthly volumes above fromVolumeUsd. Tiers are sorted ascending by fromVolumeUsd on save.">
                <InfoOutlinedIcon fontSize="small" color="action" />
              </Tooltip>
            </Stack>
            <Button
              size="small"
              startIcon={<AddIcon />}
              onClick={() => append({ fromVolumeUsd: '0.00', bpsOverride: '0.00' })}
              aria-label="add-fee-tier"
            >
              Add tier
            </Button>
          </Stack>

          {fields.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              No tiers configured — flat BPS fee applies to all volumes.
            </Typography>
          )}

          <Stack spacing={1}>
            {fields.map((field, index) => (
              <Grid container spacing={1} key={field.id} alignItems="center">
                <Grid item xs={12} md={5}>
                  <TextField
                    label="From volume (USD)"
                    fullWidth
                    size="small"
                    {...register(`feeSchedule.tiers.${index}.fromVolumeUsd`)}
                    error={!!errors?.tiers?.[index]?.fromVolumeUsd}
                    helperText={errors?.tiers?.[index]?.fromVolumeUsd?.message}
                    InputProps={{
                      startAdornment: <InputAdornment position="start">$</InputAdornment>,
                    }}
                    inputProps={{ 'aria-label': `feeSchedule.tiers.${index}.fromVolumeUsd` }}
                  />
                </Grid>
                <Grid item xs={12} md={5}>
                  <TextField
                    label="BPS override"
                    fullWidth
                    size="small"
                    {...register(`feeSchedule.tiers.${index}.bpsOverride`)}
                    error={!!errors?.tiers?.[index]?.bpsOverride}
                    helperText={errors?.tiers?.[index]?.bpsOverride?.message}
                    inputProps={{ 'aria-label': `feeSchedule.tiers.${index}.bpsOverride` }}
                  />
                </Grid>
                <Grid item xs={12} md={2} sx={{ textAlign: 'center' }}>
                  <IconButton
                    size="small"
                    onClick={() => remove(index)}
                    aria-label={`remove-fee-tier-${index}`}
                    color="error"
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Grid>
              </Grid>
            ))}
          </Stack>
        </Box>
      </Stack>
    </Box>
  );
}

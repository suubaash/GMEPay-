'use client';

import { useMemo } from 'react';
import { Controller, useFieldArray, useWatch } from 'react-hook-form';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Grid,
  IconButton,
  InputAdornment,
  InputLabel,
  MenuItem,
  Radio,
  RadioGroup,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import {
  RULE_DIRECTIONS,
  RULE_DIRECTION_LABELS,
  CROSS_BORDER_FLOOR,
  isCrossBorder,
  isMarginSumBelowFloor,
} from '@/schemas/partnerStep6RulesSchema';

/**
 * Format a decimal fraction string as a percentage with up to 2 decimal
 * places. Returns "" when the input cannot be parsed.
 *
 * @param {string|null|undefined} fractionStr  e.g. "0.0150"
 * @returns {string}  e.g. "1.50%"
 */
function fmtPct(fractionStr) {
  const n = parseFloat(fractionStr ?? '0');
  if (!Number.isFinite(n)) return '';
  return `${(n * 100).toFixed(2)}%`;
}

/**
 * Pricing Rules editor for Step 6 (Commercial Terms) — agent 6A.2.
 *
 * Renders a multi-row field-array table where each row is a pricing rule:
 *   - Scheme picker: free-text input (populated from listSchemes when mounted)
 *   - Direction radio: INBOUND / OUTBOUND / BOTH
 *   - mA (partner margin) and mB (GME margin) numeric inputs
 *   - serviceChargeUsd: flat per-transaction USD charge
 *   - Live sum preview: mA + mB rendered as percentage
 *   - Soft warning chip when mA + mB < 2% and the corridor is cross-border
 *     (hard-blocks submit in cross-border context; domestic 0% is allowed)
 *
 * The component does NOT own its own form state — it receives RHF control /
 * register / errors from the parent Step6CommercialForm and writes to the
 * "rules" field-array in the parent's form.
 *
 * @param {object}   props
 * @param {object}   props.control           RHF control from the parent form.
 * @param {Function} props.register          RHF register.
 * @param {object}   [props.errors]          RHF errors scoped to rules array.
 * @param {string[]} [props.schemeOptions]   Scheme IDs loaded from listSchemes.
 * @param {string}   [props.collectionCcy]   From currencySplit.collectionCcy.
 * @param {string}   [props.settleACcy]      From currencySplit.settleACcy.
 */
export default function RulesEditor({
  control,
  register,
  errors,
  schemeOptions = [],
  collectionCcy,
  settleACcy,
}) {
  const { fields, append, remove } = useFieldArray({
    control,
    name: 'rules',
  });

  // Watch all rules for live sum preview + margin-floor soft warnings.
  const watchedRules = useWatch({ control, name: 'rules' });

  const crossBorder = useMemo(
    () => isCrossBorder(collectionCcy, settleACcy),
    [collectionCcy, settleACcy],
  );

  /**
   * Calculate mA + mB sum for a given row index.
   * Returns { sum: number|null, pct: string, belowFloor: boolean }.
   */
  function rowMarginSummary(idx) {
    const row = watchedRules?.[idx];
    if (!row) return { sum: null, pct: '', belowFloor: false };
    const a = parseFloat(row.mA ?? '0');
    const b = parseFloat(row.mB ?? '0');
    const sum = Number.isFinite(a) && Number.isFinite(b) ? a + b : null;
    const pct = sum !== null ? `${(sum * 100).toFixed(2)}%` : '';
    const belowFloor = isMarginSumBelowFloor(collectionCcy, settleACcy, row.mA, row.mB);
    return { sum, pct, belowFloor };
  }

  return (
    <Box aria-label="rules-editor-section">
      <Stack spacing={3}>
        {/* Section header */}
        <Box>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" flexWrap="wrap" gap={1}>
            <Box>
              <Typography variant="h6" gutterBottom>
                Pricing Rules
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Define one pricing rule per scheme-direction pair. Margins are
                decimal fractions (e.g. <code>0.0150</code> = 1.50%).
                {crossBorder && (
                  <>
                    {' '}Combined floor{' '}
                    <strong>m&#x2090; + m&#x2091; &ge; {(CROSS_BORDER_FLOOR * 100).toFixed(0)}%</strong>
                    {' '}applies for cross-border rules.
                  </>
                )}
              </Typography>
            </Box>
            <Button
              size="small"
              variant="outlined"
              startIcon={<AddIcon />}
              onClick={() =>
                append({
                  schemeId:         '',
                  direction:        'OUTBOUND',
                  mA:               '0.0150',
                  mB:               '0.0050',
                  serviceChargeUsd: '0.0000',
                })
              }
              aria-label="add-rule-row"
            >
              Add rule
            </Button>
          </Stack>
        </Box>

        {/* Empty state */}
        {fields.length === 0 && (
          <Alert severity="info" variant="outlined" aria-label="rules-empty-hint">
            No pricing rules configured. Click <strong>Add rule</strong> to
            define the fee/margin structure for this partner.
          </Alert>
        )}

        {/* Rule rows */}
        <Stack spacing={3} divider={<Divider flexItem />}>
          {fields.map((field, index) => {
            const { pct, belowFloor } = rowMarginSummary(index);
            const rowErrors = errors?.[index];

            return (
              <Box key={field.id} aria-label={`rule-row-${index}`}>
                <Stack spacing={2}>
                  {/* Row header: index + remove button */}
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="subtitle2" color="text.secondary">
                      Rule {index + 1}
                    </Typography>
                    <IconButton
                      size="small"
                      onClick={() => remove(index)}
                      aria-label={`remove-rule-row-${index}`}
                      color="error"
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Stack>

                  {/* Scheme + Direction */}
                  <Grid container spacing={2}>
                    {/* Scheme */}
                    <Grid item xs={12} md={5}>
                      {schemeOptions.length > 0 ? (
                        <Controller
                          name={`rules.${index}.schemeId`}
                          control={control}
                          render={({ field: f }) => (
                            <FormControl
                              fullWidth
                              required
                              error={!!rowErrors?.schemeId}
                            >
                              <InputLabel id={`scheme-label-${index}`}>
                                Scheme
                              </InputLabel>
                              <Select
                                {...f}
                                value={f.value ?? ''}
                                labelId={`scheme-label-${index}`}
                                label="Scheme"
                                inputProps={{ 'aria-label': `rules.${index}.schemeId` }}
                              >
                                {schemeOptions.map((s) => (
                                  <MenuItem key={s} value={s}>
                                    {s}
                                  </MenuItem>
                                ))}
                              </Select>
                              <FormHelperText>
                                {rowErrors?.schemeId?.message ??
                                  'Payment scheme identifier'}
                              </FormHelperText>
                            </FormControl>
                          )}
                        />
                      ) : (
                        <TextField
                          label="Scheme"
                          fullWidth
                          required
                          {...register(`rules.${index}.schemeId`)}
                          error={!!rowErrors?.schemeId}
                          helperText={
                            rowErrors?.schemeId?.message ??
                            'Payment scheme (e.g. ZEROPAY, VIETQR)'
                          }
                          inputProps={{ 'aria-label': `rules.${index}.schemeId` }}
                        />
                      )}
                    </Grid>

                    {/* Direction */}
                    <Grid item xs={12} md={7}>
                      <Controller
                        name={`rules.${index}.direction`}
                        control={control}
                        render={({ field: f }) => (
                          <FormControl
                            component="fieldset"
                            error={!!rowErrors?.direction}
                            aria-label={`rules.${index}.direction-group`}
                          >
                            <FormLabel component="legend" sx={{ fontSize: '0.75rem' }}>
                              Direction *
                            </FormLabel>
                            <RadioGroup
                              row
                              {...f}
                              aria-label={`rules.${index}.direction`}
                            >
                              {RULE_DIRECTIONS.map((d) => (
                                <FormControlLabel
                                  key={d}
                                  value={d}
                                  control={<Radio size="small" />}
                                  label={
                                    <Typography variant="body2">
                                      {RULE_DIRECTION_LABELS[d] ?? d}
                                    </Typography>
                                  }
                                  aria-label={`rules.${index}.direction-${d}`}
                                />
                              ))}
                            </RadioGroup>
                            {rowErrors?.direction && (
                              <FormHelperText>
                                {rowErrors.direction.message}
                              </FormHelperText>
                            )}
                          </FormControl>
                        )}
                      />
                    </Grid>
                  </Grid>

                  {/* mA + mB + sum preview */}
                  <Grid container spacing={2} alignItems="flex-start">
                    {/* mA */}
                    <Grid item xs={12} md={4}>
                      <TextField
                        label="Partner margin (mA)"
                        fullWidth
                        required
                        {...register(`rules.${index}.mA`)}
                        error={!!rowErrors?.mA}
                        helperText={
                          rowErrors?.mA?.message ??
                          `Partner-side margin fraction (${fmtPct(watchedRules?.[index]?.mA)})`
                        }
                        InputProps={{
                          endAdornment: (
                            <InputAdornment position="end">
                              <Tooltip title="Decimal fraction. 0.0150 = 1.50%. Applied to the partner's side of the spread.">
                                <InfoOutlinedIcon fontSize="small" color="action" />
                              </Tooltip>
                            </InputAdornment>
                          ),
                        }}
                        inputProps={{ 'aria-label': `rules.${index}.mA` }}
                      />
                    </Grid>

                    {/* mB */}
                    <Grid item xs={12} md={4}>
                      <TextField
                        label="GME margin (mB)"
                        fullWidth
                        required
                        {...register(`rules.${index}.mB`)}
                        error={!!rowErrors?.mB}
                        helperText={
                          rowErrors?.mB?.message ??
                          `GME-side margin fraction (${fmtPct(watchedRules?.[index]?.mB)})`
                        }
                        InputProps={{
                          endAdornment: (
                            <InputAdornment position="end">
                              <Tooltip title="Decimal fraction. 0.0050 = 0.50%. Applied to GME's side of the spread.">
                                <InfoOutlinedIcon fontSize="small" color="action" />
                              </Tooltip>
                            </InputAdornment>
                          ),
                        }}
                        inputProps={{ 'aria-label': `rules.${index}.mB` }}
                      />
                    </Grid>

                    {/* serviceChargeUsd */}
                    <Grid item xs={12} md={4}>
                      <TextField
                        label="Service charge (USD)"
                        fullWidth
                        {...register(`rules.${index}.serviceChargeUsd`)}
                        error={!!rowErrors?.serviceChargeUsd}
                        helperText={
                          rowErrors?.serviceChargeUsd?.message ??
                          'Flat per-transaction charge in USD (0 = none)'
                        }
                        InputProps={{
                          startAdornment: (
                            <InputAdornment position="start">$</InputAdornment>
                          ),
                        }}
                        inputProps={{ 'aria-label': `rules.${index}.serviceChargeUsd` }}
                      />
                    </Grid>
                  </Grid>

                  {/* Sum preview + margin floor chip */}
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                    <Typography variant="body2" color="text.secondary">
                      Combined margin:
                    </Typography>
                    <Chip
                      label={pct || '—'}
                      size="small"
                      color={belowFloor ? 'warning' : 'default'}
                      aria-label={`rules.${index}.margin-sum`}
                    />

                    {belowFloor && (
                      <Chip
                        icon={<WarningAmberIcon />}
                        label={`Cross-border floor: min ${(CROSS_BORDER_FLOOR * 100).toFixed(0)}% combined`}
                        size="small"
                        color="warning"
                        variant="outlined"
                        aria-label={`rules.${index}.margin-floor-warning`}
                      />
                    )}
                  </Stack>
                </Stack>
              </Box>
            );
          })}
        </Stack>

        {/* Section-level submit-block alert when any cross-border rule fails floor */}
        {crossBorder && (watchedRules ?? []).some((row, i) =>
          isMarginSumBelowFloor(collectionCcy, settleACcy, row?.mA, row?.mB),
        ) && (
          <Alert severity="error" aria-label="rules-margin-floor-blocked">
            One or more cross-border rules have a combined margin below the
            {' '}{(CROSS_BORDER_FLOOR * 100).toFixed(0)}% floor
            (m&#x2090; + m&#x2091; &ge; {(CROSS_BORDER_FLOOR * 100).toFixed(0)}%). Correct
            the affected rows before saving.
          </Alert>
        )}
      </Stack>
    </Box>
  );
}

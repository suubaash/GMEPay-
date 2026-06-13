'use client';

import { Controller, useWatch } from 'react-hook-form';
import {
  Alert,
  Box,
  FormControl,
  FormHelperText,
  Grid,
  InputAdornment,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import {
  LICENSE_TYPES,
  LICENSE_TYPE_LABELS,
  SOAEK_PER_TXN_MAX_USD,
  SOAEK_MONTHLY_CAP_USD,
} from '@/schemas/partnerStep6CommercialSchema';

/**
 * Limits section for Step 6 (Commercial Terms).
 *
 * Renders:
 *   - Per-txn min/max USD inputs
 *   - Daily / monthly / annual cap USD inputs
 *   - License type select
 *
 * When licenseType === '소액해외송금업':
 *   - Field labels show the statutory cap (5k / 50k)
 *   - An info alert explains the restriction
 *   - The parent page's submit is blocked when values exceed caps
 *     (isSoaekBreached is exported for the parent to consume)
 *
 * @param {object}   props
 * @param {object}   props.control     RHF control from the parent form.
 * @param {Function} props.register    RHF register.
 * @param {object}   props.errors      RHF errors scoped to limits.
 */
export default function LimitsSection({ control, register, errors }) {
  const licenseType = useWatch({ control, name: 'limits.licenseType' });
  const perTxnMaxUsd = useWatch({ control, name: 'limits.perTxnMaxUsd' });
  const monthlyCapUsd = useWatch({ control, name: 'limits.monthlyCapUsd' });

  const isSoaek = licenseType === '소액해외송금업';
  const soaekBreachedPerTxn =
    isSoaek &&
    parseFloat(perTxnMaxUsd || '0') > parseFloat(SOAEK_PER_TXN_MAX_USD);
  const soaekBreachedMonthly =
    isSoaek &&
    parseFloat(monthlyCapUsd || '0') > parseFloat(SOAEK_MONTHLY_CAP_USD);

  return (
    <Box aria-label="limits-section">
      <Stack spacing={3}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Transaction Limits
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Configure per-transaction and cumulative caps. All values are in
            USD and transmitted as decimal strings.
          </Typography>
        </Box>

        {/* License type */}
        <Box>
          <Controller
            name="limits.licenseType"
            control={control}
            render={({ field }) => (
              <FormControl
                fullWidth
                required
                error={!!errors?.licenseType}
                sx={{ maxWidth: 480 }}
              >
                <InputLabel id="license-type-label">License type</InputLabel>
                <Select
                  {...field}
                  value={field.value ?? ''}
                  labelId="license-type-label"
                  label="License type"
                  inputProps={{ 'aria-label': 'limits.licenseType' }}
                >
                  {LICENSE_TYPES.map((lt) => (
                    <MenuItem key={lt} value={lt}>
                      {LICENSE_TYPE_LABELS[lt] ?? lt}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.licenseType?.message ??
                    "Partner's regulatory license category"}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Box>

        {/* Soaek restriction banner */}
        {isSoaek && (
          <Alert
            severity="info"
            variant="outlined"
            aria-label="soaek-restriction-info"
          >
            <Typography variant="body2">
              <strong>소액해외송금업</strong> statutory caps apply: per-txn max
              ≤ ${parseFloat(SOAEK_PER_TXN_MAX_USD).toLocaleString()} USD,
              monthly cap ≤ ${parseFloat(SOAEK_MONTHLY_CAP_USD).toLocaleString()} USD.
              Submit is blocked when either cap is exceeded.
            </Typography>
          </Alert>
        )}

        {/* Soaek breach errors */}
        {soaekBreachedPerTxn && (
          <Alert severity="error" aria-label="soaek-per-txn-breach">
            Per-txn maximum exceeds the 소액해외송금업 statutory limit of
            ${parseFloat(SOAEK_PER_TXN_MAX_USD).toLocaleString()} USD.
          </Alert>
        )}
        {soaekBreachedMonthly && (
          <Alert severity="error" aria-label="soaek-monthly-breach">
            Monthly cap exceeds the 소액해외송금업 statutory limit of
            ${parseFloat(SOAEK_MONTHLY_CAP_USD).toLocaleString()} USD.
          </Alert>
        )}

        {/* Per-txn min + max */}
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <TextField
              label="Per-txn minimum (USD)"
              fullWidth
              required
              {...register('limits.perTxnMinUsd')}
              error={!!errors?.perTxnMinUsd}
              helperText={
                errors?.perTxnMinUsd?.message ??
                'Smallest single-transaction amount allowed'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'limits.perTxnMinUsd' }}
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <TextField
              label={
                isSoaek
                  ? `Per-txn maximum (USD) — max $${parseFloat(SOAEK_PER_TXN_MAX_USD).toLocaleString()}`
                  : 'Per-txn maximum (USD)'
              }
              fullWidth
              required
              {...register('limits.perTxnMaxUsd')}
              error={!!errors?.perTxnMaxUsd || soaekBreachedPerTxn}
              helperText={
                errors?.perTxnMaxUsd?.message ??
                (isSoaek
                  ? `Statutory cap: $${parseFloat(SOAEK_PER_TXN_MAX_USD).toLocaleString()}`
                  : 'Largest single-transaction amount allowed')
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'limits.perTxnMaxUsd' }}
            />
          </Grid>
        </Grid>

        {/* Daily / monthly / annual caps */}
        <Grid container spacing={2}>
          <Grid item xs={12} md={4}>
            <TextField
              label="Daily cap (USD)"
              fullWidth
              required
              {...register('limits.dailyCapUsd')}
              error={!!errors?.dailyCapUsd}
              helperText={
                errors?.dailyCapUsd?.message ??
                'Maximum aggregate daily volume'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'limits.dailyCapUsd' }}
            />
          </Grid>

          <Grid item xs={12} md={4}>
            <Stack direction="row" spacing={0} sx={{ position: 'relative' }}>
              <TextField
                label={
                  isSoaek
                    ? `Monthly cap (USD) — max $${parseFloat(SOAEK_MONTHLY_CAP_USD).toLocaleString()}`
                    : 'Monthly cap (USD)'
                }
                fullWidth
                required
                {...register('limits.monthlyCapUsd')}
                error={!!errors?.monthlyCapUsd || soaekBreachedMonthly}
                helperText={
                  errors?.monthlyCapUsd?.message ??
                  (isSoaek
                    ? `Statutory cap: $${parseFloat(SOAEK_MONTHLY_CAP_USD).toLocaleString()}`
                    : 'Maximum aggregate monthly volume')
                }
                InputProps={{
                  startAdornment: <InputAdornment position="start">$</InputAdornment>,
                  endAdornment: isSoaek ? (
                    <InputAdornment position="end">
                      <Tooltip title="소액해외송금업 statutory monthly cap">
                        <InfoOutlinedIcon fontSize="small" color="warning" />
                      </Tooltip>
                    </InputAdornment>
                  ) : undefined,
                }}
                inputProps={{ 'aria-label': 'limits.monthlyCapUsd' }}
              />
            </Stack>
          </Grid>

          <Grid item xs={12} md={4}>
            <TextField
              label="Annual cap (USD)"
              fullWidth
              required
              {...register('limits.annualCapUsd')}
              error={!!errors?.annualCapUsd}
              helperText={
                errors?.annualCapUsd?.message ??
                'Maximum aggregate annual volume'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'limits.annualCapUsd' }}
            />
          </Grid>
        </Grid>
      </Stack>
    </Box>
  );
}

/**
 * Returns true when the limits values breach the 소액해외송금업 statutory caps.
 * Used by the parent page to block the submit button.
 *
 * @param {string} licenseType
 * @param {string} perTxnMaxUsd
 * @param {string} monthlyCapUsd
 * @returns {boolean}
 */
export function isSoaekLimitBreached(licenseType, perTxnMaxUsd, monthlyCapUsd) {
  if (licenseType !== '소액해외송금업') return false;
  const txnMax = parseFloat(perTxnMaxUsd || '0');
  const monthly = parseFloat(monthlyCapUsd || '0');
  return (
    txnMax > parseFloat(SOAEK_PER_TXN_MAX_USD) ||
    monthly > parseFloat(SOAEK_MONTHLY_CAP_USD)
  );
}

'use client';

import { Controller } from 'react-hook-form';
import {
  Box,
  FormControl,
  FormHelperText,
  Grid,
  InputAdornment,
  InputLabel,
  MenuItem,
  Select,
  Slider,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import {
  REFERENCE_RATE_SOURCES,
  REFERENCE_RATE_SOURCE_LABELS,
} from '@/schemas/partnerStep6CommercialSchema';

/**
 * FX Config section for Step 6 (Commercial Terms).
 *
 * Renders:
 *   - Margin BPS: slider (0..500) + numeric input (both stay in sync)
 *   - Reference rate source select (SEOUL_FX_BROKER / PARTNER_PROVIDED / MID_MARKET)
 *   - Quote hold seconds slider (60..1800, default 300) + numeric display
 *
 * Props are driven by the parent form's RHF instance.
 *
 * @param {object}   props
 * @param {object}   props.control     RHF control from the parent form.
 * @param {Function} props.register    RHF register.
 * @param {object}   props.errors      RHF errors scoped to fxConfig.
 */
export default function FxConfigSection({ control, register, errors }) {
  return (
    <Box aria-label="fx-config-section">
      <Stack spacing={3}>
        <Box>
          <Typography variant="h6" gutterBottom>
            FX Configuration
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Configure the FX margin, reference rate source, and quote-hold
            window for this partner&apos;s transactions.
          </Typography>
        </Box>

        {/* Margin BPS slider + numeric input */}
        <Box>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
              Margin (BPS)
            </Typography>
            <Tooltip title="FX margin in basis points applied on top of the reference rate. 100 BPS = 1%.">
              <InfoOutlinedIcon fontSize="small" color="action" />
            </Tooltip>
          </Stack>

          <Controller
            name="fxConfig.marginBps"
            control={control}
            render={({ field }) => {
              const numVal = parseFloat(field.value ?? '0') || 0;
              return (
                <Grid container spacing={2} alignItems="center">
                  <Grid item xs={12} md={7}>
                    <Slider
                      value={numVal}
                      min={0}
                      max={500}
                      step={1}
                      onChange={(_, v) => field.onChange(String(v))}
                      aria-label="fxConfig.marginBps.slider"
                      valueLabelDisplay="auto"
                      marks={[
                        { value: 0,   label: '0' },
                        { value: 100, label: '100' },
                        { value: 250, label: '250' },
                        { value: 500, label: '500' },
                      ]}
                    />
                  </Grid>
                  <Grid item xs={12} md={5}>
                    <TextField
                      label="Margin (BPS)"
                      fullWidth
                      size="small"
                      value={field.value ?? '0'}
                      onChange={(e) => field.onChange(e.target.value)}
                      onBlur={field.onBlur}
                      error={!!errors?.marginBps}
                      helperText={
                        errors?.marginBps?.message ?? 'e.g. "150" = 1.5%'
                      }
                      inputProps={{ 'aria-label': 'fxConfig.marginBps' }}
                    />
                  </Grid>
                </Grid>
              );
            }}
          />
        </Box>

        {/* Reference rate source */}
        <Box>
          <Controller
            name="fxConfig.referenceRateSource"
            control={control}
            render={({ field }) => (
              <FormControl
                fullWidth
                required
                error={!!errors?.referenceRateSource}
                sx={{ maxWidth: 480 }}
              >
                <InputLabel id="ref-rate-source-label">
                  Reference rate source
                </InputLabel>
                <Select
                  {...field}
                  value={field.value ?? ''}
                  labelId="ref-rate-source-label"
                  label="Reference rate source"
                  inputProps={{ 'aria-label': 'fxConfig.referenceRateSource' }}
                >
                  {REFERENCE_RATE_SOURCES.map((src) => (
                    <MenuItem key={src} value={src}>
                      {REFERENCE_RATE_SOURCE_LABELS[src] ?? src}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.referenceRateSource?.message ??
                    'Rate used as the base before applying the margin'}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Box>

        {/* Quote hold seconds */}
        <Box>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
              Quote hold (seconds)
            </Typography>
            <Tooltip title="How long a quoted rate is held before the partner must request a fresh quote. Range: 60–1800 seconds.">
              <InfoOutlinedIcon fontSize="small" color="action" />
            </Tooltip>
          </Stack>

          <Controller
            name="fxConfig.quoteHoldSeconds"
            control={control}
            render={({ field }) => {
              const numVal =
                typeof field.value === 'number'
                  ? field.value
                  : parseInt(field.value ?? '300', 10) || 300;
              return (
                <Grid container spacing={2} alignItems="center">
                  <Grid item xs={12} md={7}>
                    <Slider
                      value={numVal}
                      min={60}
                      max={1800}
                      step={30}
                      onChange={(_, v) => field.onChange(v)}
                      aria-label="fxConfig.quoteHoldSeconds.slider"
                      valueLabelDisplay="auto"
                      marks={[
                        { value: 60,   label: '1min' },
                        { value: 300,  label: '5min' },
                        { value: 900,  label: '15min' },
                        { value: 1800, label: '30min' },
                      ]}
                    />
                  </Grid>
                  <Grid item xs={12} md={5}>
                    <TextField
                      label="Quote hold (seconds)"
                      fullWidth
                      size="small"
                      type="number"
                      value={numVal}
                      onChange={(e) =>
                        field.onChange(parseInt(e.target.value, 10) || 300)
                      }
                      onBlur={field.onBlur}
                      error={!!errors?.quoteHoldSeconds}
                      helperText={
                        errors?.quoteHoldSeconds?.message ??
                        `${Math.floor(numVal / 60)}m ${numVal % 60}s`
                      }
                      inputProps={{
                        min: 60,
                        max: 1800,
                        'aria-label': 'fxConfig.quoteHoldSeconds',
                      }}
                    />
                  </Grid>
                </Grid>
              );
            }}
          />
        </Box>
      </Stack>
    </Box>
  );
}

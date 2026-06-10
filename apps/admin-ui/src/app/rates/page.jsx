'use client';

import { useCallback, useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Grid2 as Grid,
  InputLabel,
  MenuItem,
  Radio,
  RadioGroup,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import MoneyDisplay from '@/components/MoneyDisplay';
import ErrorAlert from '@/components/ErrorAlert';
import { useSnackbar } from '@/components/SnackbarProvider';
import { ratePreviewSchema } from '@/schemas/ratePreviewSchema';
import { useAppDispatch, useAppSelector } from '@/store';
import { previewRate } from '@/store/ratesSlice';
import { fetchPartners } from '@/store/partnersSlice';

/**
 * /rates — Manual rate quote preview.
 *
 * POSTs { fromCcy, toCcy, amount, direction, partnerId } to
 * /v1/admin/rates/preview and renders the 5-step pivot fields of the
 * resulting RateQuotePreview:
 *
 *   { collectionAmount, collectionCurrency,
 *     payoutAmount, payoutCurrency,
 *     collectionUsd, payoutUsdCost,
 *     collectionMarginUsd, payoutMarginUsd,
 *     offerRateColl, crossRate,
 *     shortCircuit, quotedAt }
 *
 * All money is rendered via <MoneyDisplay>. Defensive defaults (`?? ''`,
 * `?? 0`) so a partial payload never throws.
 */
const SUPPORTED_CURRENCIES = ['KRW', 'USD', 'MNT', 'JPY', 'VND'];

function ResultCell({ label, children, sub }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Box sx={{ mt: 0.5 }}>{children}</Box>
      {sub ? (
        <Typography variant="caption" color="text.secondary">
          {sub}
        </Typography>
      ) : null}
    </Box>
  );
}

export default function RatesPreviewPage() {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { preview, loading, error } = useAppSelector((s) => s.rates);
  const { items: partners, loading: partnersLoading } = useAppSelector(
    (s) => s.partners,
  );

  const reloadPartners = useCallback(() => {
    dispatch(fetchPartners());
  }, [dispatch]);

  useEffect(() => {
    reloadPartners();
  }, [reloadPartners]);

  const {
    control,
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(ratePreviewSchema),
    defaultValues: {
      fromCcy: 'KRW',
      toCcy: 'USD',
      amount: '100000',
      direction: 'INBOUND',
      partnerId: '',
    },
  });

  const onSubmit = async (values) => {
    try {
      await dispatch(
        previewRate({
          fromCcy: values.fromCcy,
          toCcy: values.toCcy,
          amount: values.amount,
          direction: values.direction,
          partnerId: values.partnerId,
        }),
      ).unwrap();
      snackbar.success('Rate quote previewed');
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      snackbar.error(`Preview failed: ${message}`);
    }
  };

  const busy = loading || isSubmitting;
  const partnerList = Array.isArray(partners) ? partners : [];

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Rates Preview
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Run a manual quote against the rate-fx 5-step pivot engine. No
        prefund deduction, no transaction created — preview only.
      </Typography>

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 5 }}>
          <Card>
            <CardContent>
              <Typography variant="h4" gutterBottom>
                Quote inputs
              </Typography>
              <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
                <Stack spacing={2}>
                  <Controller
                    name="fromCcy"
                    control={control}
                    render={({ field }) => (
                      <FormControl fullWidth error={!!errors.fromCcy}>
                        <InputLabel id="from-ccy-label">From currency</InputLabel>
                        <Select
                          {...field}
                          labelId="from-ccy-label"
                          label="From currency"
                          inputProps={{ 'aria-label': 'From currency' }}
                        >
                          {SUPPORTED_CURRENCIES.map((c) => (
                            <MenuItem key={c} value={c}>
                              {c}
                            </MenuItem>
                          ))}
                        </Select>
                        <FormHelperText>{errors.fromCcy?.message ?? ' '}</FormHelperText>
                      </FormControl>
                    )}
                  />

                  <Controller
                    name="toCcy"
                    control={control}
                    render={({ field }) => (
                      <FormControl fullWidth error={!!errors.toCcy}>
                        <InputLabel id="to-ccy-label">To currency</InputLabel>
                        <Select
                          {...field}
                          labelId="to-ccy-label"
                          label="To currency"
                          inputProps={{ 'aria-label': 'To currency' }}
                        >
                          {SUPPORTED_CURRENCIES.map((c) => (
                            <MenuItem key={c} value={c}>
                              {c}
                            </MenuItem>
                          ))}
                        </Select>
                        <FormHelperText>{errors.toCcy?.message ?? ' '}</FormHelperText>
                      </FormControl>
                    )}
                  />

                  <TextField
                    label="Amount"
                    fullWidth
                    required
                    {...register('amount')}
                    error={!!errors.amount}
                    helperText={
                      errors.amount?.message ??
                      'Decimal string, e.g. 100000 (KRW) or 100.50 (USD)'
                    }
                    inputProps={{ 'aria-label': 'Amount', inputMode: 'decimal' }}
                  />

                  <Controller
                    name="direction"
                    control={control}
                    render={({ field }) => (
                      <FormControl error={!!errors.direction}>
                        <FormLabel id="direction-label">Direction</FormLabel>
                        <RadioGroup
                          {...field}
                          row
                          aria-labelledby="direction-label"
                        >
                          <FormControlLabel
                            value="INBOUND"
                            control={<Radio />}
                            label="INBOUND"
                          />
                          <FormControlLabel
                            value="OUTBOUND"
                            control={<Radio />}
                            label="OUTBOUND"
                          />
                        </RadioGroup>
                        <FormHelperText>{errors.direction?.message ?? ' '}</FormHelperText>
                      </FormControl>
                    )}
                  />

                  <Controller
                    name="partnerId"
                    control={control}
                    render={({ field }) => (
                      <FormControl fullWidth error={!!errors.partnerId}>
                        <InputLabel id="partner-id-label">Partner</InputLabel>
                        <Select
                          {...field}
                          labelId="partner-id-label"
                          label="Partner"
                          inputProps={{ 'aria-label': 'Partner' }}
                          disabled={partnersLoading}
                        >
                          {partnerList.length === 0 ? (
                            <MenuItem value="" disabled>
                              {partnersLoading ? 'Loading…' : 'No partners'}
                            </MenuItem>
                          ) : (
                            partnerList.map((p) => (
                              <MenuItem key={p.partnerId} value={p.partnerId}>
                                {p.partnerId} ({p.type ?? '—'})
                              </MenuItem>
                            ))
                          )}
                        </Select>
                        <FormHelperText>
                          {errors.partnerId?.message ?? 'Partner whose margins apply'}
                        </FormHelperText>
                      </FormControl>
                    )}
                  />

                  <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
                    <Button
                      type="submit"
                      variant="contained"
                      disabled={busy}
                      startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
                    >
                      Preview quote
                    </Button>
                  </Box>
                </Stack>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 7 }}>
          <ErrorAlert
            message={error}
            title="Could not preview rate"
          />
          {preview ? (
            <Card>
              <CardContent>
                <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                  <Typography variant="h4" sx={{ flexGrow: 1 }}>
                    Quote result
                  </Typography>
                  <Chip
                    size="small"
                    label={preview.shortCircuit ? 'SHORT-CIRCUIT' : '5-STEP PIVOT'}
                    color={preview.shortCircuit ? 'warning' : 'info'}
                  />
                </Box>
                <Typography variant="caption" color="text.secondary">
                  Quoted at {preview.quotedAt ?? '—'}
                </Typography>
                <Grid container spacing={2} sx={{ mt: 1 }}>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell label="Collection (from partner)">
                      <MoneyDisplay
                        amount={preview.collectionAmount}
                        currency={preview.collectionCurrency ?? ''}
                      />
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell label="Payout (to customer)">
                      <MoneyDisplay
                        amount={preview.payoutAmount}
                        currency={preview.payoutCurrency ?? ''}
                      />
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell label="Collection USD">
                      <MoneyDisplay amount={preview.collectionUsd} currency="USD" />
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell label="Payout USD cost">
                      <MoneyDisplay amount={preview.payoutUsdCost} currency="USD" />
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell label="Collection margin (USD)">
                      <MoneyDisplay amount={preview.collectionMarginUsd} currency="USD" />
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell label="Payout margin (USD)">
                      <MoneyDisplay amount={preview.payoutMarginUsd} currency="USD" />
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell
                      label="Offer rate (collection)"
                      sub="Rate offered to the partner before payout conversion."
                    >
                      <Typography sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
                        {preview.offerRateColl ?? '—'}
                      </Typography>
                    </ResultCell>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <ResultCell
                      label="Cross rate"
                      sub="USD-pivot cross rate; n/a when shortCircuit=true."
                    >
                      <Typography sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
                        {preview.crossRate ?? '—'}
                      </Typography>
                    </ResultCell>
                  </Grid>
                </Grid>
              </CardContent>
            </Card>
          ) : !error && !loading ? (
            <Card variant="outlined">
              <CardContent>
                <Typography color="text.secondary">
                  Submit the form to preview a rate quote. No transaction will
                  be created.
                </Typography>
              </CardContent>
            </Card>
          ) : null}
        </Grid>
      </Grid>
    </Box>
  );
}

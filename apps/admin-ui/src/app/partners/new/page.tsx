'use client';

import { useRouter } from 'next/navigation';
import { Controller, useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  FormControl,
  FormHelperText,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import RoundingModeSelect from '@/components/RoundingModeSelect';
import { useSnackbar } from '@/components/SnackbarProvider';
import { partnerSchema, type PartnerFormValues } from '@/schemas/partnerSchema';
import { PARTNER_TYPES, type PartnerType, type RoundingMode } from '@/api/types';
import { useAppDispatch, useAppSelector } from '@/store';
import { createPartner } from '@/store/partnersSlice';

/**
 * Partner CREATE form.
 *
 * The most important page in the Ops/Admin Portal: it surfaces the
 * per-partner settlement rounding mode (docs/MONEY_CONVENTION.md) at
 * onboarding time. The selected mode is locked onto every future
 * transaction's settlement-booking by payment-executor/settlement-reconciliation,
 * and the residual versus the precise amount is posted as REVENUE_ROUNDING gain
 * or loss in revenue-ledger.
 *
 * On success the global SnackbarProvider fires a green toast and the router
 * navigates to /partners. On error a red toast carries the BFF message
 * (e.g. 409 Conflict for duplicate IDs) and the form stays put so the
 * operator can correct and retry.
 *
 * Defaults:
 *   type:                 LOCAL
 *   settlementCurrency:   KRW
 *   settlementRoundingMode: HALF_UP  (matches Partner domain record default)
 */
export default function NewPartnerPage() {
  const router = useRouter();
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { saving } = useAppSelector((s) => s.partners);

  const {
    control,
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<PartnerFormValues>({
    resolver: yupResolver(partnerSchema),
    defaultValues: {
      partnerId: '',
      type: 'LOCAL',
      settlementCurrency: 'KRW',
      settlementRoundingMode: 'HALF_UP',
    },
  });

  const onSubmit = async (values: PartnerFormValues) => {
    try {
      await dispatch(
        createPartner({
          partnerId: values.partnerId,
          type: values.type as PartnerType,
          settlementCurrency: values.settlementCurrency,
          settlementRoundingMode: values.settlementRoundingMode as RoundingMode,
        }),
      ).unwrap();
      snackbar.success(`Partner ${values.partnerId} created`);
      router.push('/partners');
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      snackbar.error(`Create failed: ${message}`);
    }
  };

  const busy = saving || isSubmitting;

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        New partner
      </Typography>
      <Card sx={{ maxWidth: 640 }}>
        <CardContent>
          <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
            <Stack spacing={2}>
              <TextField
                label="Partner ID"
                fullWidth
                required
                {...register('partnerId')}
                error={!!errors.partnerId}
                helperText={
                  errors.partnerId?.message ?? 'e.g. GME_KR_001 (uppercase, 3-32 chars)'
                }
                inputProps={{ 'aria-label': 'Partner ID' }}
              />

              <Controller
                name="type"
                control={control}
                render={({ field }) => (
                  <FormControl fullWidth error={!!errors.type}>
                    <InputLabel id="partner-type-label">Partner type</InputLabel>
                    <Select
                      {...field}
                      labelId="partner-type-label"
                      label="Partner type"
                      id="partner-type"
                    >
                      {PARTNER_TYPES.map((t) => (
                        <MenuItem key={t} value={t}>
                          {t}
                        </MenuItem>
                      ))}
                    </Select>
                    <FormHelperText>
                      {errors.type?.message ??
                        'LOCAL = domestic (KRW, no prefunding); OVERSEAS = USD-prefunded.'}
                    </FormHelperText>
                  </FormControl>
                )}
              />

              <TextField
                label="Settlement currency (ISO-4217)"
                fullWidth
                required
                {...register('settlementCurrency')}
                error={!!errors.settlementCurrency}
                helperText={
                  errors.settlementCurrency?.message ??
                  '3-letter uppercase code, e.g. KRW, USD, JPY'
                }
                inputProps={{ maxLength: 3, style: { textTransform: 'uppercase' } }}
              />

              <Controller
                name="settlementRoundingMode"
                control={control}
                render={({ field }) => (
                  <RoundingModeSelect
                    value={field.value as RoundingMode}
                    onChange={field.onChange}
                    name={field.name}
                    error={!!errors.settlementRoundingMode}
                    helperText={
                      errors.settlementRoundingMode?.message ??
                      'How this partner books its settlement liability. Default HALF_UP. See MONEY_CONVENTION.md.'
                    }
                  />
                )}
              />

              <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
                <Button onClick={() => router.push('/partners')}>Cancel</Button>
                <Button
                  type="submit"
                  variant="contained"
                  disabled={busy}
                  startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
                >
                  Create partner
                </Button>
              </Box>
            </Stack>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
}

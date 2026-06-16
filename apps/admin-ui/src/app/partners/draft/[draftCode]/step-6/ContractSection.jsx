'use client';

import { Controller } from 'react-hook-form';
import {
  Box,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Grid,
  Radio,
  RadioGroup,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { DATE_FLOOR, yearsFromTodayISO } from '@/components/DateField';
import {
  REFUND_CHARGEBACK_POLICIES,
  REFUND_CHARGEBACK_POLICY_LABELS,
} from '@/schemas/partnerStep6CommercialSchema';

/**
 * Contract section for Step 6 (Commercial Terms).
 *
 * Renders:
 *   - effectiveFrom + effectiveTo date pickers (type="date" inputs)
 *   - autoRenewal switch
 *   - noticePeriodDays numeric input
 *   - refundChargebackPolicy radio group
 *   - terminationReason free-text input (optional)
 *
 * @param {object}   props
 * @param {object}   props.control     RHF control from the parent form.
 * @param {Function} props.register    RHF register.
 * @param {object}   props.errors      RHF errors scoped to contract.
 */
export default function ContractSection({ control, register, errors }) {
  return (
    <Box aria-label="contract-section">
      <Stack spacing={3}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Contract Terms
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Set the contract validity window, renewal behaviour, and
            cost-bearing policy for refunds and chargebacks.
          </Typography>
        </Box>

        {/* Effective from / to */}
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <TextField
              label="Effective from"
              type="date"
              fullWidth
              required
              InputLabelProps={{ shrink: true }}
              {...register('contract.effectiveFrom')}
              error={!!errors?.effectiveFrom}
              helperText={
                errors?.effectiveFrom?.message ??
                'Date the commercial terms take effect (YYYY-MM-DD)'
              }
              inputProps={{ min: DATE_FLOOR, max: yearsFromTodayISO(10), 'aria-label': 'contract.effectiveFrom' }}
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <TextField
              label="Effective to (optional)"
              type="date"
              fullWidth
              InputLabelProps={{ shrink: true }}
              {...register('contract.effectiveTo')}
              error={!!errors?.effectiveTo}
              helperText={
                errors?.effectiveTo?.message ??
                'Leave blank for open-ended contracts'
              }
              inputProps={{ min: DATE_FLOOR, max: yearsFromTodayISO(20), 'aria-label': 'contract.effectiveTo' }}
            />
          </Grid>
        </Grid>

        {/* Auto-renewal + notice period */}
        <Grid container spacing={2} alignItems="flex-start">
          <Grid item xs={12} md={6}>
            <Controller
              name="contract.autoRenewal"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={!!field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                      inputProps={{ 'aria-label': 'contract.autoRenewal' }}
                      color="primary"
                    />
                  }
                  label={
                    <Stack direction="row" spacing={0.5} alignItems="center">
                      <span>Auto-renewal</span>
                      <Tooltip title="When enabled, the contract automatically renews at the end of each term unless the notice period has passed.">
                        <InfoOutlinedIcon fontSize="small" color="action" />
                      </Tooltip>
                    </Stack>
                  }
                  aria-label="contract.autoRenewal-toggle"
                />
              )}
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <TextField
              label="Notice period (days)"
              type="number"
              fullWidth
              required
              {...register('contract.noticePeriodDays', { valueAsNumber: true })}
              error={!!errors?.noticePeriodDays}
              helperText={
                errors?.noticePeriodDays?.message ??
                'Days notice required before termination or non-renewal'
              }
              inputProps={{
                min: 0,
                'aria-label': 'contract.noticePeriodDays',
              }}
            />
          </Grid>
        </Grid>

        {/* Refund / chargeback policy */}
        <Box>
          <Controller
            name="contract.refundChargebackPolicy"
            control={control}
            render={({ field }) => (
              <FormControl
                component="fieldset"
                error={!!errors?.refundChargebackPolicy}
                aria-label="refund-chargeback-policy-group"
              >
                <FormLabel component="legend" sx={{ mb: 1, fontWeight: 600 }}>
                  Refund &amp; chargeback policy
                </FormLabel>
                <RadioGroup
                  {...field}
                  aria-label="contract.refundChargebackPolicy"
                >
                  {REFUND_CHARGEBACK_POLICIES.map((policy) => (
                    <FormControlLabel
                      key={policy}
                      value={policy}
                      control={<Radio size="small" />}
                      label={REFUND_CHARGEBACK_POLICY_LABELS[policy] ?? policy}
                      aria-label={`refundChargebackPolicy-${policy}`}
                    />
                  ))}
                </RadioGroup>
                {errors?.refundChargebackPolicy && (
                  <FormHelperText>
                    {errors.refundChargebackPolicy.message}
                  </FormHelperText>
                )}
              </FormControl>
            )}
          />
        </Box>

        {/* Termination reason */}
        <TextField
          label="Termination reason (optional)"
          fullWidth
          multiline
          minRows={2}
          maxRows={5}
          {...register('contract.terminationReason')}
          error={!!errors?.terminationReason}
          helperText={
            errors?.terminationReason?.message ??
            'Free-text note explaining why this contract is / will be terminated'
          }
          inputProps={{ 'aria-label': 'contract.terminationReason' }}
        />
      </Stack>
    </Box>
  );
}

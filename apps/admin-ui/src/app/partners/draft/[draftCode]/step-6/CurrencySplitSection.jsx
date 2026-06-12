'use client';

import { useMemo } from 'react';
import { Controller, useWatch } from 'react-hook-form';
import {
  Alert,
  Box,
  Chip,
  FormControl,
  FormHelperText,
  Grid,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Typography,
} from '@mui/material';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import SyncIcon from '@mui/icons-material/Sync';
import { isCrossBorder } from '@/schemas/partnerStep6RulesSchema';

/**
 * ISO-4217 currency codes offered in the picker.
 *
 * Covers all active GME corridors. Add entries here when new corridors open.
 * The full ISO-4217 list would make the UX unusable; this curated set matches
 * the currencies already present in the config-registry scheme table.
 */
export const SUPPORTED_CURRENCIES = [
  'AED', 'AUD', 'BDT', 'CAD', 'CHF', 'CNY', 'EUR',
  'GBP', 'HKD', 'IDR', 'INR', 'JPY', 'KHR', 'KRW',
  'LKR', 'MYR', 'MXN', 'NPR', 'NZD', 'PHP', 'PKR',
  'SGD', 'THB', 'USD', 'VND',
];

/** Human-readable label for each currency code. */
export const CURRENCY_LABELS = {
  AED: 'AED — UAE Dirham',
  AUD: 'AUD — Australian Dollar',
  BDT: 'BDT — Bangladeshi Taka',
  CAD: 'CAD — Canadian Dollar',
  CHF: 'CHF — Swiss Franc',
  CNY: 'CNY — Chinese Yuan',
  EUR: 'EUR — Euro',
  GBP: 'GBP — British Pound',
  HKD: 'HKD — Hong Kong Dollar',
  IDR: 'IDR — Indonesian Rupiah',
  INR: 'INR — Indian Rupee',
  JPY: 'JPY — Japanese Yen',
  KHR: 'KHR — Cambodian Riel',
  KRW: 'KRW — Korean Won',
  LKR: 'LKR — Sri Lankan Rupee',
  MYR: 'MYR — Malaysian Ringgit',
  MXN: 'MXN — Mexican Peso',
  NPR: 'NPR — Nepalese Rupee',
  NZD: 'NZD — New Zealand Dollar',
  PHP: 'PHP — Philippine Peso',
  PKR: 'PKR — Pakistani Rupee',
  SGD: 'SGD — Singapore Dollar',
  THB: 'THB — Thai Baht',
  USD: 'USD — US Dollar',
  VND: 'VND — Vietnamese Dong',
};

/**
 * Currency-Split section for Step 6 (Commercial Terms) — agent 6A.2.
 *
 * Renders two MUI Select pickers:
 *   - collection_ccy : the currency in which the partner collects from senders
 *   - settle_a_ccy   : the currency GME uses when settling with the partner
 *
 * When both currencies are the same (pure-domestic corridor) the section
 * shows a "No cross-border conversion" info note. When they differ it shows
 * a chip indicating the conversion direction, and the 2% margin floor applies
 * to the pricing rules below (enforced by RulesEditor's soft warning).
 *
 * Persistence model (ADR-013 Expand phase): these fields live on the partner
 * root row (V016 columns). The parent form's onSubmit calls
 * adminApi.patchDraftStep6Rules which carries the full rule set; the currency
 * split is saved separately via adminApi.patchDraftStep6CurrencySplit (which
 * the parent form wires). Both fields are nullable server-side and backfilled
 * from settlement_currency for pre-Slice-6 rows — the Contract phase drop of
 * settlement_currency is a future release (ADR-013).
 *
 * @param {object}   props
 * @param {object}   props.control   RHF control from the parent form.
 * @param {Function} props.register  RHF register.
 * @param {object}   [props.errors]  RHF errors scoped to currencySplit.
 */
export default function CurrencySplitSection({ control, register, errors }) {
  const collectionCcy = useWatch({ control, name: 'currencySplit.collectionCcy' });
  const settleACcy    = useWatch({ control, name: 'currencySplit.settleACcy' });

  const crossBorder = useMemo(
    () => isCrossBorder(collectionCcy, settleACcy),
    [collectionCcy, settleACcy],
  );

  return (
    <Box aria-label="currency-split-section">
      <Stack spacing={3}>
        {/* Section header */}
        <Box>
          <Typography variant="h6" gutterBottom>
            Currency Split
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Define the collection currency (what the partner receives from
            senders) and the settlement currency (what GME pays the partner).
            When these differ, the 2% combined margin floor (
            <em>m&#x2090; + m&#x2091;</em>) applies to all cross-border pricing
            rules below.
          </Typography>
        </Box>

        {/* Two selects */}
        <Grid container spacing={2} alignItems="flex-start">
          {/* Collection currency */}
          <Grid item xs={12} md={5}>
            <Controller
              name="currencySplit.collectionCcy"
              control={control}
              render={({ field }) => (
                <FormControl
                  fullWidth
                  required
                  error={!!errors?.collectionCcy}
                >
                  <InputLabel id="collection-ccy-label">
                    Collection currency
                  </InputLabel>
                  <Select
                    {...field}
                    value={field.value ?? ''}
                    labelId="collection-ccy-label"
                    label="Collection currency"
                    inputProps={{ 'aria-label': 'currencySplit.collectionCcy' }}
                  >
                    {SUPPORTED_CURRENCIES.map((ccy) => (
                      <MenuItem key={ccy} value={ccy}>
                        {CURRENCY_LABELS[ccy] ?? ccy}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors?.collectionCcy?.message ??
                      'ISO-4217 code — what the partner collects from senders'}
                  </FormHelperText>
                </FormControl>
              )}
            />
          </Grid>

          {/* Arrow indicator */}
          <Grid
            item
            xs={12}
            md={2}
            sx={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              pt: { md: 1.5 },
            }}
          >
            {crossBorder ? (
              <SwapHorizIcon
                color="primary"
                fontSize="large"
                aria-label="cross-border-arrow"
              />
            ) : (
              <SyncIcon
                color="action"
                fontSize="large"
                aria-label="same-currency-icon"
              />
            )}
          </Grid>

          {/* Settlement currency */}
          <Grid item xs={12} md={5}>
            <Controller
              name="currencySplit.settleACcy"
              control={control}
              render={({ field }) => (
                <FormControl
                  fullWidth
                  required
                  error={!!errors?.settleACcy}
                >
                  <InputLabel id="settle-a-ccy-label">
                    Settlement currency
                  </InputLabel>
                  <Select
                    {...field}
                    value={field.value ?? ''}
                    labelId="settle-a-ccy-label"
                    label="Settlement currency"
                    inputProps={{ 'aria-label': 'currencySplit.settleACcy' }}
                  >
                    {SUPPORTED_CURRENCIES.map((ccy) => (
                      <MenuItem key={ccy} value={ccy}>
                        {CURRENCY_LABELS[ccy] ?? ccy}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors?.settleACcy?.message ??
                      'ISO-4217 code — what GME settles with the partner in'}
                  </FormHelperText>
                </FormControl>
              )}
            />
          </Grid>
        </Grid>

        {/* Contextual hint */}
        {collectionCcy && settleACcy && (
          crossBorder ? (
            <Alert
              severity="info"
              variant="outlined"
              icon={<SwapHorizIcon fontSize="inherit" />}
              aria-label="cross-border-hint"
            >
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  Cross-border corridor:
                </Typography>
                <Chip label={collectionCcy} size="small" color="primary" variant="outlined" />
                <Typography variant="body2">to</Typography>
                <Chip label={settleACcy} size="small" color="secondary" variant="outlined" />
                <Typography variant="body2">
                  — the 2% combined margin floor applies to each pricing rule
                  below.
                </Typography>
              </Stack>
            </Alert>
          ) : (
            <Alert
              severity="success"
              variant="outlined"
              icon={<SyncIcon fontSize="inherit" />}
              aria-label="no-cross-border-hint"
            >
              <Typography variant="body2">
                <strong>No cross-border conversion</strong> — collection and
                settlement are both{' '}
                <Chip label={collectionCcy} size="small" color="success" variant="outlined" />.
                The 2% margin floor does not apply; zero-margin rules are
                permitted.
              </Typography>
            </Alert>
          )
        )}
      </Stack>
    </Box>
  );
}

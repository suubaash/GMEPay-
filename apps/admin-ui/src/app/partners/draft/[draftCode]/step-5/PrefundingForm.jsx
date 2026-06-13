'use client';

import { useEffect, useMemo } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  FormControl,
  FormControlLabel,
  FormHelperText,
  FormLabel,
  Grid,
  InputAdornment,
  InputLabel,
  LinearProgress,
  MenuItem,
  Radio,
  RadioGroup,
  Select,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep5 } from '@/store/draftsSlice';
import { fetchBankAccounts } from '@/store/bankAccountsSlice';
import { fetchPrefundingConfig } from '@/store/prefundingConfigSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import partnerStep5Schema, {
  FUNDING_MODELS,
  FUNDING_MODEL_LABELS,
} from '@/schemas/partnerStep5Schema';

/**
 * Slice 5 — Step 5 (Prefunding) form.
 *
 * OVERSEAS-only: if draft.type !== 'OVERSEAS' an info panel is shown instead
 * of the form, with a note that the operator can skip to step 6.
 *
 * Wire shape sent on submit (PATCH /api/v1/admin/partners/draft/{code}/step-5):
 *   {
 *     fundingModel, openingBalanceUsd, lowBalanceThresholdUsd,
 *     alertTier70, alertTier85, alertTier95,
 *     creditLimitUsd, autoSuspendOnBreach,
 *     floatTopUpBankAccountId, topUpReferencePattern, collateralAmountUsd,
 *   }
 *
 * All money fields are BigDecimal strings on the wire (docs/MONEY_CONVENTION.md).
 *
 * Features:
 *   - Funding-model radio (PREFUNDED / POSTPAID / HYBRID)
 *   - Opening balance + low-balance threshold inputs (USD decimal strings)
 *   - Alert tier toggles at 70 / 85 / 95 percent with a live gauge preview
 *   - Credit limit + auto-suspend switch
 *   - Float top-up bank account picker (FLOAT_TOPUP purpose, with hint when empty)
 *   - Top-up reference pattern with live preview
 *   - Collateral amount
 *
 * @param {object}   props
 * @param {object}   props.draft        PartnerView the wizard is editing.
 * @param {string}   props.partnerCode  URL-pinned identifier.
 * @param {Function} [props.onSaved]    Called with updated PartnerView on success.
 */
export default function PrefundingForm({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { saving } = useAppSelector((s) => s.drafts);
  const { byCode: bankAccountsByCode } = useAppSelector((s) => s.bankAccounts);
  const { configByCode, loadingByCode } = useAppSelector((s) => s.prefundingConfig);

  const savedConfig = configByCode[partnerCode] ?? null;
  const configLoading = loadingByCode[partnerCode] ?? false;

  // Filter bank accounts to FLOAT_TOPUP purpose only.
  const allAccounts = useMemo(
    () => bankAccountsByCode[partnerCode] ?? [],
    [bankAccountsByCode, partnerCode],
  );
  const floatTopUpAccounts = useMemo(
    () => allAccounts.filter((a) => a.purpose === 'FLOAT_TOPUP'),
    [allAccounts],
  );

  // ── OVERSEAS guard ──────────────────────────────────────────────────────────
  if (draft && draft.type && draft.type !== 'OVERSEAS') {
    return (
      <Alert
        severity="info"
        variant="outlined"
        aria-label="non-overseas-info"
        sx={{ my: 2 }}
      >
        <Typography variant="body1" sx={{ fontWeight: 600 }}>
          Prefunding applies to OVERSEAS partners only
        </Typography>
        <Typography variant="body2" sx={{ mt: 0.5 }}>
          This partner is of type <strong>{draft.type}</strong> and does not
          require a prefunding configuration. You can skip directly to Step 6
          (Commercial) using the Back / Next controls below.
        </Typography>
      </Alert>
    );
  }

  return (
    <PrefundingFormInner
      draft={draft}
      partnerCode={partnerCode}
      onSaved={onSaved}
      dispatch={dispatch}
      snackbar={snackbar}
      saving={saving}
      floatTopUpAccounts={floatTopUpAccounts}
      savedConfig={savedConfig}
      configLoading={configLoading}
    />
  );
}

/**
 * Inner form component — separated so the OVERSEAS guard can return early
 * without hook-order issues (hooks must not be called conditionally).
 */
function PrefundingFormInner({
  draft,
  partnerCode,
  onSaved,
  dispatch,
  snackbar,
  saving,
  floatTopUpAccounts,
  savedConfig,
  configLoading,
}) {
  const {
    control,
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(partnerStep5Schema),
    defaultValues: defaultFormValues(),
    mode: 'onBlur',
  });

  // Fetch existing config + bank accounts when the section mounts.
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchPrefundingConfig(partnerCode)).catch(() => {});
      dispatch(fetchBankAccounts(partnerCode)).catch(() => {});
    }
  }, [partnerCode, dispatch]);

  // Re-populate the form when saved config arrives from the BFF.
  useEffect(() => {
    if (savedConfig) {
      reset({
        fundingModel:            savedConfig.fundingModel            ?? 'PREFUNDED',
        openingBalanceUsd:       savedConfig.openingBalanceUsd       ?? '0.00',
        lowBalanceThresholdUsd:  savedConfig.lowBalanceThresholdUsd  ?? '1000.00',
        alertTier70:             savedConfig.alertTier70             ?? true,
        alertTier85:             savedConfig.alertTier85             ?? true,
        alertTier95:             savedConfig.alertTier95             ?? true,
        creditLimitUsd:          savedConfig.creditLimitUsd          ?? '0.00',
        autoSuspendOnBreach:     savedConfig.autoSuspendOnBreach     ?? false,
        floatTopUpBankAccountId: savedConfig.floatTopUpBankAccountId ?? null,
        topUpReferencePattern:   savedConfig.topUpReferencePattern   ?? 'TOPUP-{partner_code}-',
        collateralAmountUsd:     savedConfig.collateralAmountUsd     ?? '0.00',
      });
    }
  }, [savedConfig, reset]);

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    const body = {
      fundingModel:            values.fundingModel,
      openingBalanceUsd:       values.openingBalanceUsd.trim(),
      lowBalanceThresholdUsd:  values.lowBalanceThresholdUsd.trim(),
      alertTier70:             !!values.alertTier70,
      alertTier85:             !!values.alertTier85,
      alertTier95:             !!values.alertTier95,
      creditLimitUsd:          values.creditLimitUsd.trim(),
      autoSuspendOnBreach:     !!values.autoSuspendOnBreach,
      floatTopUpBankAccountId: values.floatTopUpBankAccountId || null,
      topUpReferencePattern:   values.topUpReferencePattern.trim(),
      collateralAmountUsd:     values.collateralAmountUsd.trim(),
    };
    try {
      const result = await dispatch(patchStep5({ partnerCode, body })).unwrap();
      snackbar.success(`Step 5 saved for ${partnerCode}`);
      if (typeof onSaved === 'function') onSaved(result);
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  const busy = saving || isSubmitting;

  // Live-watch fields for the gauge + pattern preview.
  const openingBalance    = watch('openingBalanceUsd');
  const threshold         = watch('lowBalanceThresholdUsd');
  const tier70            = watch('alertTier70');
  const tier85            = watch('alertTier85');
  const tier95            = watch('alertTier95');
  const referencePattern  = watch('topUpReferencePattern');

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="prefunding-form"
    >
      <Stack spacing={4}>
        {/* Header */}
        <Box>
          <Typography variant="h6" gutterBottom>
            Prefunding Configuration
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 5 — configure the funding model, float balance thresholds, and
            alert tiers for this OVERSEAS partner. All monetary fields are in
            USD and transmitted as decimal strings.
          </Typography>
        </Box>

        {configLoading && (
          <Box sx={{ py: 1 }} aria-label="loading-prefunding-config">
            <CircularProgress size={20} />
          </Box>
        )}

        {/* ── Funding model ─────────────────────────────────────────────── */}
        <Box>
          <Controller
            name="fundingModel"
            control={control}
            render={({ field }) => (
              <FormControl
                component="fieldset"
                error={!!errors.fundingModel}
                aria-label="funding-model-group"
              >
                <FormLabel component="legend" sx={{ mb: 1, fontWeight: 600 }}>
                  Funding model
                </FormLabel>
                <RadioGroup
                  {...field}
                  aria-label="fundingModel"
                >
                  {FUNDING_MODELS.map((model) => (
                    <FormControlLabel
                      key={model}
                      value={model}
                      control={<Radio size="small" />}
                      label={FUNDING_MODEL_LABELS[model] ?? model}
                      aria-label={`fundingModel-${model}`}
                    />
                  ))}
                </RadioGroup>
                {errors.fundingModel && (
                  <FormHelperText>{errors.fundingModel.message}</FormHelperText>
                )}
              </FormControl>
            )}
          />
        </Box>

        <Divider />

        {/* ── Balance inputs ────────────────────────────────────────────── */}
        <Grid container spacing={2}>
          <Grid item xs={12} md={4}>
            <TextField
              label="Opening balance (USD)"
              fullWidth
              required
              {...register('openingBalanceUsd')}
              error={!!errors.openingBalanceUsd}
              helperText={
                errors.openingBalanceUsd?.message ??
                'Initial float balance in USD (decimal string)'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'openingBalanceUsd' }}
            />
          </Grid>

          <Grid item xs={12} md={4}>
            <TextField
              label="Low-balance threshold (USD)"
              fullWidth
              required
              {...register('lowBalanceThresholdUsd')}
              error={!!errors.lowBalanceThresholdUsd}
              helperText={
                errors.lowBalanceThresholdUsd?.message ??
                'Trigger alerts when balance falls below this amount'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'lowBalanceThresholdUsd' }}
            />
          </Grid>

          <Grid item xs={12} md={4}>
            <TextField
              label="Credit limit (USD)"
              fullWidth
              required
              {...register('creditLimitUsd')}
              error={!!errors.creditLimitUsd}
              helperText={
                errors.creditLimitUsd?.message ??
                'Maximum credit exposure allowed (0 for PREFUNDED)'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'creditLimitUsd' }}
            />
          </Grid>

          <Grid item xs={12} md={4}>
            <TextField
              label="Collateral amount (USD)"
              fullWidth
              required
              {...register('collateralAmountUsd')}
              error={!!errors.collateralAmountUsd}
              helperText={
                errors.collateralAmountUsd?.message ??
                'Security deposit held against credit exposure'
              }
              InputProps={{
                startAdornment: <InputAdornment position="start">$</InputAdornment>,
              }}
              inputProps={{ 'aria-label': 'collateralAmountUsd' }}
            />
          </Grid>
        </Grid>

        <Divider />

        {/* ── Alert tiers + live gauge ───────────────────────────────────── */}
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
            Alert tiers
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Enable alert notifications when the float balance falls to these
            percentages of the opening balance.
          </Typography>

          <Stack direction="row" spacing={3} sx={{ mb: 3 }} flexWrap="wrap">
            <Controller
              name="alertTier70"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={!!field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                      inputProps={{ 'aria-label': 'alertTier70' }}
                    />
                  }
                  label="70% alert"
                  aria-label="alertTier70-toggle"
                />
              )}
            />
            <Controller
              name="alertTier85"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={!!field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                      inputProps={{ 'aria-label': 'alertTier85' }}
                    />
                  }
                  label="85% alert"
                  aria-label="alertTier85-toggle"
                />
              )}
            />
            <Controller
              name="alertTier95"
              control={control}
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch
                      checked={!!field.value}
                      onChange={(e) => field.onChange(e.target.checked)}
                      inputProps={{ 'aria-label': 'alertTier95' }}
                    />
                  }
                  label="95% alert"
                  aria-label="alertTier95-toggle"
                />
              )}
            />
          </Stack>

          {/* Live gauge preview */}
          <TierGauge
            openingBalance={openingBalance}
            threshold={threshold}
            tier70={tier70}
            tier85={tier85}
            tier95={tier95}
          />
        </Box>

        <Divider />

        {/* ── Auto-suspend ──────────────────────────────────────────────── */}
        <Box>
          <Controller
            name="autoSuspendOnBreach"
            control={control}
            render={({ field }) => (
              <FormControlLabel
                control={
                  <Switch
                    checked={!!field.value}
                    onChange={(e) => field.onChange(e.target.checked)}
                    inputProps={{ 'aria-label': 'autoSuspendOnBreach' }}
                    color="warning"
                  />
                }
                label={
                  <Stack direction="row" spacing={0.5} alignItems="center">
                    <span>Auto-suspend partner on balance breach</span>
                    <Tooltip title="When enabled, the partner is automatically suspended when the balance falls below the threshold and no credit headroom remains.">
                      <InfoOutlinedIcon fontSize="small" color="action" />
                    </Tooltip>
                  </Stack>
                }
                aria-label="autoSuspendOnBreach-toggle"
              />
            )}
          />
        </Box>

        <Divider />

        {/* ── Float top-up bank account picker ─────────────────────────── */}
        <Box>
          <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
            Float top-up bank account
          </Typography>

          {floatTopUpAccounts.length === 0 ? (
            <Alert severity="info" variant="outlined" aria-label="no-topup-accounts">
              No FLOAT_TOPUP bank accounts found for this partner. Add one in
              Step 4 (Banking) and return here to select it.
            </Alert>
          ) : (
            <Controller
              name="floatTopUpBankAccountId"
              control={control}
              render={({ field }) => (
                <FormControl
                  fullWidth
                  error={!!errors.floatTopUpBankAccountId}
                  sx={{ maxWidth: 480 }}
                >
                  <InputLabel id="float-topup-account-label">
                    Float top-up account
                  </InputLabel>
                  <Select
                    {...field}
                    value={field.value ?? ''}
                    labelId="float-topup-account-label"
                    label="Float top-up account"
                    inputProps={{ 'aria-label': 'floatTopUpBankAccountId' }}
                  >
                    <MenuItem value="">
                      <em>None</em>
                    </MenuItem>
                    {floatTopUpAccounts.map((acct) => (
                      <MenuItem key={acct.id} value={acct.id}>
                        {acct.bankName} — {acct.ibanOrAccountNumber} ({acct.currency})
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors.floatTopUpBankAccountId?.message ??
                      'Account used to receive float top-up transfers'}
                  </FormHelperText>
                </FormControl>
              )}
            />
          )}
        </Box>

        {/* ── Top-up reference pattern ──────────────────────────────────── */}
        <Box>
          <TextField
            label="Top-up reference pattern"
            fullWidth
            required
            {...register('topUpReferencePattern')}
            error={!!errors.topUpReferencePattern}
            helperText={
              errors.topUpReferencePattern?.message ??
              'Must contain {partner_code}. Use {partner_code} and {date} as placeholders.'
            }
            inputProps={{ 'aria-label': 'topUpReferencePattern' }}
            sx={{ maxWidth: 480 }}
          />
          <ReferencePatternPreview
            pattern={referencePattern}
            partnerCode={partnerCode}
          />
        </Box>

        <Divider />

        {/* Submit */}
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
            aria-label="save-step-5"
          >
            Save &amp; next
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

/**
 * Live gauge showing where the 70 / 85 / 95 % tiers sit relative to the
 * opening balance.
 *
 * The gauge is a labelled LinearProgress bar with tick marks.  When
 * opening-balance is 0 or unparseable, the gauge shows a grey placeholder.
 *
 * @param {object}  props
 * @param {string}  props.openingBalance   Current openingBalanceUsd field value.
 * @param {string}  props.threshold        Current lowBalanceThresholdUsd value.
 * @param {boolean} props.tier70
 * @param {boolean} props.tier85
 * @param {boolean} props.tier95
 */
export function TierGauge({ openingBalance, threshold, tier70, tier85, tier95 }) {
  const balance = parseFloat(openingBalance ?? '0') || 0;
  const thresh  = parseFloat(threshold ?? '0') || 0;

  // Current fill % = (threshold / opening) * 100, clamped to 0-100.
  const fillPct = balance > 0 ? Math.min(100, Math.max(0, (thresh / balance) * 100)) : 0;

  const tiers = [
    { pct: 70, enabled: tier70, label: '70%' },
    { pct: 85, enabled: tier85, label: '85%' },
    { pct: 95, enabled: tier95, label: '95%' },
  ];

  return (
    <Box
      aria-label="tier-gauge"
      sx={{ maxWidth: 560 }}
    >
      <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block' }}>
        Balance gauge — threshold sits at{' '}
        <strong>
          {balance > 0 ? `${fillPct.toFixed(1)}%` : '—'}
        </strong>{' '}
        of opening balance
      </Typography>

      {/* Bar */}
      <Box sx={{ position: 'relative', height: 24, bgcolor: 'grey.200', borderRadius: 1, overflow: 'visible' }}>
        {/* Fill */}
        <Box
          sx={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: `${fillPct}%`,
            bgcolor: fillPct >= 95 ? 'error.main' : fillPct >= 85 ? 'warning.main' : 'success.main',
            borderRadius: 1,
            transition: 'width 0.3s ease',
          }}
          aria-label="gauge-fill"
        />

        {/* Tier tick marks */}
        {tiers.map(({ pct, enabled, label }) => (
          <Box
            key={pct}
            aria-label={`tier-tick-${pct}`}
            sx={{
              position: 'absolute',
              left: `${pct}%`,
              top: -4,
              bottom: -4,
              width: 2,
              bgcolor: enabled ? 'primary.main' : 'grey.400',
              opacity: enabled ? 1 : 0.35,
              transform: 'translateX(-50%)',
            }}
          >
            <Typography
              variant="caption"
              sx={{
                position: 'absolute',
                top: -18,
                left: '50%',
                transform: 'translateX(-50%)',
                color: enabled ? 'primary.main' : 'text.disabled',
                fontWeight: enabled ? 700 : 400,
                whiteSpace: 'nowrap',
              }}
            >
              {label}
            </Typography>
          </Box>
        ))}
      </Box>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 0.5 }}>
        <Typography variant="caption" color="text.secondary">$0</Typography>
        <Typography variant="caption" color="text.secondary">
          {balance > 0 ? `$${balance.toLocaleString()}` : '—'}
        </Typography>
      </Box>
    </Box>
  );
}

/**
 * Live preview of the top-up reference pattern with substitutions applied.
 *
 * Substitutes {partner_code} with the actual partnerCode and {date} with
 * today's date in YYYYMMDD format so the operator can verify the final
 * reference string before saving.
 *
 * @param {object} props
 * @param {string} props.pattern      The raw pattern value.
 * @param {string} props.partnerCode  The partner's code.
 */
export function ReferencePatternPreview({ pattern, partnerCode }) {
  if (!pattern || typeof pattern !== 'string') return null;

  const today = new Date().toISOString().slice(0, 10).replace(/-/g, '');
  const preview = pattern
    .replace(/\{partner_code\}/g, partnerCode ?? 'PARTNER')
    .replace(/\{date\}/g, today);

  return (
    <Box sx={{ mt: 1 }} aria-label="reference-pattern-preview">
      <Typography variant="caption" color="text.secondary">
        Preview:{' '}
        <strong style={{ fontFamily: 'monospace' }}>{preview}</strong>
      </Typography>
    </Box>
  );
}

/** Default form values for a blank draft. */
function defaultFormValues() {
  return {
    fundingModel:            'PREFUNDED',
    openingBalanceUsd:       '0.00',
    lowBalanceThresholdUsd:  '1000.00',
    alertTier70:             true,
    alertTier85:             true,
    alertTier95:             true,
    creditLimitUsd:          '0.00',
    autoSuspendOnBreach:     false,
    floatTopUpBankAccountId: null,
    topUpReferencePattern:   'TOPUP-{partner_code}-',
    collateralAmountUsd:     '0.00',
  };
}

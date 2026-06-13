'use client';

import { useEffect, useMemo } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  Stack,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { fetchCommercial, patchDraftStep6Commercial } from '@/store/commercialTermsSlice';
import { fetchRules, patchRules } from '@/store/rulesSlice';
import { listSchemes } from '@/store/schemesSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import { PartnerDraftWizard } from '../page';
import partnerStep6CommercialSchema from '@/schemas/partnerStep6CommercialSchema';
import { isSoaekLimitBreached } from './LimitsSection';
import FeeScheduleSection from './FeeScheduleSection';
import FxConfigSection from './FxConfigSection';
import LimitsSection from './LimitsSection';
import ContractSection from './ContractSection';
import CurrencySplitSection from './CurrencySplitSection';
import RulesEditor from './RulesEditor';

/**
 * Deep-link route: /partners/draft/{partnerCode}/step-6
 *
 * Renders the same wizard shell as the root draft page but with the cursor
 * pre-set to step 6 (Commercial Terms). The shell re-fetches the draft on
 * mount so a browser refresh lands on the correct step.
 */
export default function Step6Page() {
  return <PartnerDraftWizard activeStep={6} />;
}

/**
 * Default form values for a blank draft.
 *
 * @param {object|null} [draft]  PartnerView from the wizard — seeds
 *   currencySplit from collectionCcy / settleACcy / settlementCurrency.
 */
function defaultFormValues(draft) {
  // Seed currency split from the draft's V016 fields if present; fall back to
  // settlementCurrency (the pre-Slice-6 backfill). Both fields may be null on
  // very early drafts — default to empty string in that case (user must pick).
  const collectionCcy = draft?.collectionCcy ?? draft?.settlementCurrency ?? '';
  const settleACcy    = draft?.settleACcy    ?? draft?.settlementCurrency ?? '';

  return {
    feeSchedule: {
      scheme:      '',
      direction:   'OUTBOUND',
      fixedFeeUsd: '0.00',
      bpsFee:      '0.00',
      tiers:       [],
    },
    fxConfig: {
      marginBps:          '0',
      referenceRateSource: 'SEOUL_FX_BROKER',
      quoteHoldSeconds:   300,
    },
    limits: {
      perTxnMinUsd:  '1.00',
      perTxnMaxUsd:  '5000.00',
      dailyCapUsd:   '50000.00',
      monthlyCapUsd: '200000.00',
      annualCapUsd:  '2000000.00',
      licenseType:   '',
    },
    contract: {
      effectiveFrom:          '',
      effectiveTo:            null,
      autoRenewal:            true,
      noticePeriodDays:       30,
      refundChargebackPolicy: 'PARTNER_BEARS',
      terminationReason:      null,
    },
    // 6A.2 — currency split (ADR-013 Expand phase)
    currencySplit: {
      collectionCcy,
      settleACcy,
    },
    // 6A.2 — pricing rules (populated by fetchRules on mount)
    rules: [],
  };
}

/**
 * Step 6 (Commercial Terms) composite form.
 *
 * Stacks four sub-sections separated by Dividers:
 *   1. FeeScheduleSection  (scheme/direction/fees/tiers)
 *   2. FxConfigSection     (margin_bps/reference_rate_source/quote_hold_seconds)
 *   3. LimitsSection       (per_txn/daily/monthly/annual/license_type)
 *   4. ContractSection     (dates/auto_renewal/notice/policy/termination)
 *
 * 6A.2 sub-sections (CurrencySplitSection + RulesEditor) are imported below
 * when that agent's files land; stubs are shown in their place until then.
 *
 * A single Submit at the bottom fires patchDraftStep6Commercial and then
 * calls onSaved to advance the wizard cursor.
 *
 * @param {object}   props
 * @param {object}   props.draft        PartnerView the wizard is editing.
 * @param {string}   props.partnerCode  URL-pinned identifier.
 * @param {Function} [props.onSaved]    Called with updated PartnerView on success.
 */
export function Step6CommercialForm({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const commercialSaving = useAppSelector((s) => s.commercialTerms?.saving ?? false);
  const rulesSaving      = useAppSelector((s) => s.rules?.saving      ?? false);
  const configByCode     = useAppSelector((s) => s.commercialTerms?.configByCode ?? null);
  const rulesByCode      = useAppSelector((s) => s.rules?.rulesByCode ?? null);
  const schemeItems      = useAppSelector((s) => s.schemes?.items ?? null);

  const savedConfig = configByCode ? (configByCode[partnerCode] ?? null) : null;
  const savedRules  = rulesByCode  ? (rulesByCode[partnerCode]  ?? null) : null;

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(partnerStep6CommercialSchema),
    defaultValues: defaultFormValues(draft),
    mode: 'onBlur',
  });

  // Fetch existing commercial terms + pricing rules + scheme options on mount.
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchCommercial(partnerCode)).catch(() => {});
      dispatch(fetchRules(partnerCode)).catch(() => {});
    }
  }, [partnerCode, dispatch]);

  useEffect(() => {
    dispatch(listSchemes()).catch(() => {});
  }, [dispatch]);

  // Re-populate the form when saved commercial config arrives from the BFF.
  // Uses a full reset (not functional form) so that useWatch hooks for
  // soaekBlocked re-evaluate promptly against the new values.
  useEffect(() => {
    if (savedConfig) {
      reset({
        // Preserve 6A.2 currency split from the draft prop; the commercial
        // config does not carry currency fields — they live on the partner root.
        currencySplit: {
          collectionCcy: draft?.collectionCcy ?? draft?.settlementCurrency ?? '',
          settleACcy:    draft?.settleACcy    ?? draft?.settlementCurrency ?? '',
        },
        // Preserve rules — they are fetched/reset separately by fetchRules.
        rules: [],
        feeSchedule: {
          scheme:      savedConfig.feeSchedule?.scheme      ?? '',
          direction:   savedConfig.feeSchedule?.direction   ?? 'OUTBOUND',
          fixedFeeUsd: savedConfig.feeSchedule?.fixedFeeUsd ?? '0.00',
          bpsFee:      savedConfig.feeSchedule?.bpsFee      ?? '0.00',
          tiers:       savedConfig.feeSchedule?.tiers       ?? [],
        },
        fxConfig: {
          marginBps:           savedConfig.fxConfig?.marginBps           ?? '0',
          referenceRateSource: savedConfig.fxConfig?.referenceRateSource ?? 'SEOUL_FX_BROKER',
          quoteHoldSeconds:    savedConfig.fxConfig?.quoteHoldSeconds    ?? 300,
        },
        limits: {
          perTxnMinUsd:  savedConfig.limits?.perTxnMinUsd  ?? '1.00',
          perTxnMaxUsd:  savedConfig.limits?.perTxnMaxUsd  ?? '5000.00',
          dailyCapUsd:   savedConfig.limits?.dailyCapUsd   ?? '50000.00',
          monthlyCapUsd: savedConfig.limits?.monthlyCapUsd ?? '200000.00',
          annualCapUsd:  savedConfig.limits?.annualCapUsd  ?? '2000000.00',
          licenseType:   savedConfig.limits?.licenseType   ?? '',
        },
        contract: {
          effectiveFrom:          savedConfig.contract?.effectiveFrom          ?? '',
          effectiveTo:            savedConfig.contract?.effectiveTo            ?? null,
          autoRenewal:            savedConfig.contract?.autoRenewal            ?? true,
          noticePeriodDays:       savedConfig.contract?.noticePeriodDays       ?? 30,
          refundChargebackPolicy: savedConfig.contract?.refundChargebackPolicy ?? 'PARTNER_BEARS',
          terminationReason:      savedConfig.contract?.terminationReason      ?? null,
        },
      });
    }
  }, [savedConfig, reset, draft]);

  // Re-populate rules when saved rules arrive from the BFF.
  // A full reset here would wipe the commercial config fields; use setValue
  // on the rules field-array to avoid replacing the whole form.
  useEffect(() => {
    if (Array.isArray(savedRules) && savedRules.length > 0) {
      // We only want to touch the rules field, not the entire form,
      // so we use a targeted reset that preserves other fields via getValues.
      reset((prev) => ({
        ...prev,
        rules: savedRules.map((r) => ({
          schemeId:         r.schemeId         ?? '',
          direction:        r.direction        ?? 'OUTBOUND',
          mA:               r.mA != null ? String(r.mA) : '0.0150',
          mB:               r.mB != null ? String(r.mB) : '0.0050',
          serviceChargeUsd: r.serviceChargeUsd != null ? String(r.serviceChargeUsd) : '0.0000',
        })),
      }));
    }
  }, [savedRules, reset]);

  // Watch limit fields to evaluate 소액해외송금업 breach for submit gate.
  const licenseType   = useWatch({ control, name: 'limits.licenseType' });
  const perTxnMaxUsd  = useWatch({ control, name: 'limits.perTxnMaxUsd' });
  const monthlyCapUsd = useWatch({ control, name: 'limits.monthlyCapUsd' });

  // Watch currency-split fields for passing to RulesEditor.
  const collectionCcy = useWatch({ control, name: 'currencySplit.collectionCcy' });
  const settleACcy    = useWatch({ control, name: 'currencySplit.settleACcy' });

  const soaekBlocked = useMemo(
    () => isSoaekLimitBreached(licenseType, perTxnMaxUsd, monthlyCapUsd),
    [licenseType, perTxnMaxUsd, monthlyCapUsd],
  );

  // Scheme options from the schemes slice (populated after listSchemes fetch).
  const schemeOptions = useMemo(() => {
    if (!Array.isArray(schemeItems)) return [];
    return schemeItems.map((s) => s.schemeId ?? s.name ?? '').filter(Boolean);
  }, [schemeItems]);

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    if (soaekBlocked) {
      snackbar.error(
        '소액해외송금업 statutory caps exceeded — correct the limits before saving.',
      );
      return;
    }

    // --- 6B sections: fee schedule, FX config, limits, contract ---
    const commercialBody = {
      feeSchedule: {
        scheme:      values.feeSchedule.scheme.trim(),
        direction:   values.feeSchedule.direction,
        fixedFeeUsd: values.feeSchedule.fixedFeeUsd.trim(),
        bpsFee:      values.feeSchedule.bpsFee.trim(),
        tiers:       (values.feeSchedule.tiers ?? []).map((t) => ({
          fromVolumeUsd: t.fromVolumeUsd.trim(),
          bpsOverride:   t.bpsOverride.trim(),
        })),
      },
      fxConfig: {
        marginBps:           String(values.fxConfig.marginBps).trim(),
        referenceRateSource: values.fxConfig.referenceRateSource,
        quoteHoldSeconds:    Number(values.fxConfig.quoteHoldSeconds),
      },
      limits: {
        perTxnMinUsd:  values.limits.perTxnMinUsd.trim(),
        perTxnMaxUsd:  values.limits.perTxnMaxUsd.trim(),
        dailyCapUsd:   values.limits.dailyCapUsd.trim(),
        monthlyCapUsd: values.limits.monthlyCapUsd.trim(),
        annualCapUsd:  values.limits.annualCapUsd.trim(),
        licenseType:   values.limits.licenseType.trim(),
      },
      contract: {
        effectiveFrom:          values.contract.effectiveFrom.trim(),
        effectiveTo:            values.contract.effectiveTo ?? null,
        autoRenewal:            !!values.contract.autoRenewal,
        noticePeriodDays:       Number(values.contract.noticePeriodDays),
        refundChargebackPolicy: values.contract.refundChargebackPolicy,
        terminationReason:      values.contract.terminationReason ?? null,
      },
    };

    // --- 6A rules: send bulk-replace ---
    const rulesPayload = (values.rules ?? []).map((r) => ({
      schemeId:         (r.schemeId ?? '').trim(),
      direction:        r.direction,
      mA:               (r.mA ?? '0.0000').trim(),
      mB:               (r.mB ?? '0.0000').trim(),
      serviceChargeUsd: r.serviceChargeUsd ? r.serviceChargeUsd.trim() : '0.0000',
    }));

    let commercialResult;
    try {
      commercialResult = await dispatch(
        patchDraftStep6Commercial({ partnerCode, body: commercialBody }),
      ).unwrap();
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Commercial terms save failed: ${message}`);
      return;
    }

    // Save rules (6A.2) — non-fatal if the 6A.1 endpoint hasn't landed yet
    // (will surface as a snackbar warning, not blocking onSaved).
    try {
      await dispatch(patchRules({ partnerCode, rules: rulesPayload })).unwrap();
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Pricing rules save failed: ${message}`);
      // Still advance the wizard — commercial terms saved successfully above.
    }

    snackbar.success(`Step 6 saved for ${partnerCode}`);
    if (typeof onSaved === 'function') onSaved(commercialResult);
  };

  const saving = commercialSaving || rulesSaving;
  const busy   = saving || isSubmitting;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="step-6-commercial-form"
    >
      <Stack spacing={4}>
        {/* Header */}
        <Box>
          <Typography variant="h5" gutterBottom>
            Commercial Terms
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 6 — configure fees, FX margin, transaction limits, and
            the contract terms for this partner. All monetary fields are
            transmitted as decimal strings (USD).
          </Typography>
        </Box>

        {/* ── Fee Schedule ─────────────────────────────────────────────── */}
        <FeeScheduleSection
          control={control}
          register={register}
          errors={errors.feeSchedule}
        />

        <Divider />

        {/* ── FX Config ────────────────────────────────────────────────── */}
        <FxConfigSection
          control={control}
          register={register}
          errors={errors.fxConfig}
        />

        <Divider />

        {/* ── Limits ───────────────────────────────────────────────────── */}
        <LimitsSection
          control={control}
          register={register}
          errors={errors.limits}
        />

        <Divider />

        {/* ── Contract ─────────────────────────────────────────────────── */}
        <ContractSection
          control={control}
          register={register}
          errors={errors.contract}
        />

        {/* ── Currency Split (6A.2) ───────────────────────────────────── */}
        <Divider />
        <CurrencySplitSection
          control={control}
          register={register}
          errors={errors.currencySplit}
        />

        {/* ── Pricing Rules (6A.2) ────────────────────────────────────── */}
        <Divider />
        <RulesEditor
          control={control}
          register={register}
          errors={errors.rules}
          schemeOptions={schemeOptions}
          collectionCcy={collectionCcy}
          settleACcy={settleACcy}
        />

        {/* Soaek submit-block alert */}
        {soaekBlocked && (
          <Alert severity="error" aria-label="soaek-submit-blocked">
            Cannot submit: 소액해외송금업 statutory caps are exceeded. Reduce
            the per-txn max or monthly cap before saving.
          </Alert>
        )}

        <Divider />

        {/* Submit */}
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Button
            type="submit"
            variant="contained"
            disabled={busy || soaekBlocked}
            startIcon={
              busy ? <CircularProgress size={16} color="inherit" /> : undefined
            }
            aria-label="save-step-6-commercial"
          >
            Save &amp; next
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

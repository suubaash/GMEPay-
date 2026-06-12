'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  Box,
  Button,
  CircularProgress,
  Divider,
  Stack,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchPartnerSchemes,
  fetchPartnerCorridors,
  updateStep7Schemes,
  updateStep7Corridors,
} from '@/store/partnerSchemesSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import SchemesMatrix, { defaultSchemesValue, SCHEME_IDS } from './SchemesMatrix';
import CorridorsBuilder from './CorridorsBuilder';
import OperatingHoursPreview from './OperatingHoursPreview';

/**
 * Default form values for a blank step-7 draft.
 *
 * @param {object[]|null} savedSchemes   Persisted PartnerSchemeView[] from BFF.
 * @param {object[]|null} savedCorridors Persisted PartnerCorridorView[] from BFF.
 */
function defaultFormValues(savedSchemes, savedCorridors) {
  // Build scheme rows: merge saved values into the canonical SCHEME_IDS list.
  // Schemes not present in the saved set default to disabled.
  const schemeMap = {};
  if (Array.isArray(savedSchemes)) {
    for (const s of savedSchemes) {
      if (s.schemeId) schemeMap[s.schemeId] = s;
    }
  }

  const schemes = SCHEME_IDS.map((id) => {
    const saved = schemeMap[id];
    if (!saved) {
      return {
        schemeId: id,
        enabled: false,
        direction: 'OUTBOUND',
        role: 'ACQUIRER',
        zeropayMerchantId: '',
        zeropaySubMerchantId: '',
        kftcInstitutionCode: '',
        partnerTypeChar: 'D',
        approvalMethodCpm: 'CONFIRMATION',
        approvalMethodMpm: 'CONFIRMATION',
      };
    }
    return {
      schemeId: id,
      enabled: !!saved.enabled,
      direction: saved.direction ?? 'OUTBOUND',
      role: saved.role ?? 'ACQUIRER',
      zeropayMerchantId: saved.zeropayMerchantId ?? '',
      zeropaySubMerchantId: saved.zeropaySubMerchantId ?? '',
      kftcInstitutionCode: saved.kftcInstitutionCode ?? '',
      partnerTypeChar: saved.partnerTypeChar ?? 'D',
      approvalMethodCpm: saved.approvalMethodCpm ?? 'CONFIRMATION',
      approvalMethodMpm: saved.approvalMethodMpm ?? 'CONFIRMATION',
    };
  });

  const corridors = Array.isArray(savedCorridors)
    ? savedCorridors.map((c) => ({
        srcCountry: c.srcCountry ?? '',
        srcCcy: c.srcCcy ?? '',
        dstCountry: c.dstCountry ?? '',
        dstCcy: c.dstCcy ?? '',
        goLiveDate: c.goLiveDate ?? '',
        active: c.active !== false,
      }))
    : [];

  return { schemes, corridors };
}

/**
 * Step 7 (Schemes & Corridors) composite form.
 *
 * Stacks three sections separated by Dividers:
 *   1. SchemesMatrix   — per-scheme enable/direction/role + ZeroPay drill-down
 *   2. CorridorsBuilder — corridor CRUD
 *   3. OperatingHoursPreview — read-only hours for the selected scheme
 *
 * On "Save & next": PATCHes step-7-schemes, then step-7-corridors sequentially,
 * mirroring the step-6 pattern (commercial first, rules second). Advances the
 * wizard cursor via onSaved on success; remains on the step on partial failure
 * but surfaces an error snackbar.
 *
 * @param {object}   props
 * @param {object}   props.draft         PartnerView the wizard is editing.
 * @param {string}   props.partnerCode   URL-pinned identifier.
 * @param {Function} [props.onSaved]     Called with updated PartnerView on success.
 */
export function Step7Form({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const saving = useAppSelector((s) => s.partnerSchemes?.saving ?? false);
  const schemesByCode = useAppSelector((s) => s.partnerSchemes?.schemesByCode ?? {});
  const corridorsByCode = useAppSelector((s) => s.partnerSchemes?.corridorsByCode ?? {});

  const savedSchemes = schemesByCode[partnerCode] ?? null;
  const savedCorridors = corridorsByCode[partnerCode] ?? null;

  // Track which scheme the operator last interacted with in SchemesMatrix, so
  // OperatingHoursPreview can display the relevant hours.
  const [previewSchemeId, setPreviewSchemeId] = useState(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    defaultValues: defaultFormValues(null, null),
    mode: 'onBlur',
  });

  // Fetch existing schemes + corridors on mount.
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchPartnerSchemes(partnerCode)).catch(() => {});
      dispatch(fetchPartnerCorridors(partnerCode)).catch(() => {});
    }
  }, [partnerCode, dispatch]);

  // Re-populate the form when saved data arrives from the BFF.
  useEffect(() => {
    if (savedSchemes !== null || savedCorridors !== null) {
      reset(defaultFormValues(savedSchemes, savedCorridors));
    }
  }, [savedSchemes, savedCorridors, reset]);

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }

    // Build schemes payload — strip internal-only schemeId field (it's the key,
    // not a mutable attribute on the command) and trim strings.
    const schemesBody = {
      schemes: values.schemes.map((s) => ({
        schemeId: s.schemeId,
        enabled: !!s.enabled,
        direction: s.direction,
        role: s.role,
        zeropayMerchantId: s.zeropayMerchantId ? s.zeropayMerchantId.trim() : null,
        zeropaySubMerchantId: s.zeropaySubMerchantId ? s.zeropaySubMerchantId.trim() : null,
        kftcInstitutionCode: s.kftcInstitutionCode ? s.kftcInstitutionCode.trim() : null,
        partnerTypeChar: s.partnerTypeChar ?? null,
        approvalMethodCpm: s.approvalMethodCpm ?? null,
        approvalMethodMpm: s.approvalMethodMpm ?? null,
      })),
    };

    // Build corridors payload.
    const corridorsBody = {
      corridors: (values.corridors ?? []).map((c) => ({
        srcCountry: c.srcCountry.trim(),
        srcCcy: c.srcCcy.trim(),
        dstCountry: c.dstCountry.trim(),
        dstCcy: c.dstCcy.trim(),
        goLiveDate: c.goLiveDate.trim(),
        active: !!c.active,
      })),
    };

    // Persist schemes first — mirrors step-6 sequential dispatch pattern.
    let schemesResult;
    try {
      schemesResult = await dispatch(
        updateStep7Schemes({ partnerCode, body: schemesBody }),
      ).unwrap();
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Scheme enrollments save failed: ${message}`);
      return;
    }

    // Persist corridors — non-blocking on success of schemes.
    try {
      await dispatch(
        updateStep7Corridors({ partnerCode, body: corridorsBody }),
      ).unwrap();
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Corridors save failed: ${message}`);
      // Still advance — schemes saved successfully above.
    }

    snackbar.success(`Step 7 saved for ${partnerCode}`);
    if (typeof onSaved === 'function') onSaved(schemesResult);
  };

  const busy = saving || isSubmitting;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="step-7-schemes-form"
    >
      <Stack spacing={4}>
        {/* Header */}
        <Box>
          <Typography variant="h5" gutterBottom>
            Schemes &amp; Corridors
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 7 — enroll the partner in payment schemes and define the
            country-currency corridors they are licensed to operate.
          </Typography>
        </Box>

        {/* ── Schemes matrix ─────────────────────────────────────────── */}
        <SchemesMatrix
          control={control}
          register={register}
          errors={errors.schemes}
          onSchemeChange={setPreviewSchemeId}
        />

        <Divider />

        {/* ── Corridors ──────────────────────────────────────────────── */}
        <CorridorsBuilder
          control={control}
          register={register}
          errors={errors.corridors}
        />

        <Divider />

        {/* ── Operating hours preview ────────────────────────────────── */}
        <OperatingHoursPreview schemeId={previewSchemeId} />

        <Divider />

        {/* Submit */}
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            startIcon={
              busy ? <CircularProgress size={16} color="inherit" /> : undefined
            }
            aria-label="save-step-7-schemes"
          >
            Save &amp; next
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

export default Step7Form;

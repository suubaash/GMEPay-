'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import {
  Box,
  CircularProgress,
  Divider,
  FormControl,
  FormHelperText,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import {
  fetchSettlementConfig,
  fetchSettlementPreview,
  patchDraftStep4Settlement,
} from '@/store/settlementConfigSlice';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Settlement cycle (T+N) options, 0..5.
 */
export const CYCLE_OPTIONS = [0, 1, 2, 3, 4, 5];

/**
 * Settlement method options with display labels. The roster MUST match
 * config-registry V013's CHECK constraint exactly — any value not in this
 * list will be rejected at the DB layer with a constraint violation.
 *
 * Earlier this UI shipped a generic SWIFT/ACH/FPS/RTGS/SEPA/CHAPS/OTHER
 * roster that diverged from the V013 enum and would 500 on save. Fixed by
 * mirroring the DB enum and labelling each rail with its real-world name.
 */
export const SETTLEMENT_METHODS = [
  'SWIFT_MT103',
  'KR_FIRM_BANKING',
  'BAKONG',
  'NAPAS_247',
  'PROMPT_PAY',
  'FAST_SG',
  'OTHER',
];

export const SETTLEMENT_METHOD_LABELS = {
  SWIFT_MT103: 'SWIFT MT103 (international wire)',
  KR_FIRM_BANKING: '펌뱅킹 (Korea firm banking)',
  BAKONG: 'Bakong (Cambodia)',
  NAPAS_247: 'NAPAS 247 (Vietnam)',
  PROMPT_PAY: 'PromptPay (Thailand)',
  FAST_SG: 'FAST (Singapore)',
  OTHER: 'Other / custom',
};

/**
 * Common Asia/Pacific timezones shown in the selector.
 * Operators can still type an IANA name directly if it is not in this list.
 */
export const ASIA_TIMEZONES = [
  'Asia/Seoul',
  'Asia/Tokyo',
  'Asia/Hong_Kong',
  'Asia/Singapore',
  'Asia/Bangkok',
  'Asia/Kolkata',
  'Asia/Karachi',
  'Asia/Dubai',
  'Asia/Dhaka',
  'Asia/Kathmandu',
  'Asia/Colombo',
  'Asia/Yangon',
  'Asia/Ho_Chi_Minh',
  'Asia/Manila',
  'Asia/Jakarta',
  'Asia/Kuala_Lumpur',
  'Asia/Taipei',
  'Asia/Shanghai',
  'Asia/Ulaanbaatar',
  'Pacific/Auckland',
  'Australia/Sydney',
  'UTC',
];

const DEFAULT_VALUES = {
  cycleTPlusN: 1,
  cutoffTime: '17:00',
  cutoffTimezone: 'Asia/Seoul',
  settlementMethod: 'SWIFT_MT103',
};

/**
 * Debounce helper. Returns a stable callback that fires `fn` after `delay`ms
 * once calls stop. Uses useRef so the function identity is stable.
 */
function useDebounce(fn, delay) {
  const timerRef = useRef(null);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  return useCallback(
    (...args) => {
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => {
        fnRef.current(...args);
      }, delay);
    },
    [delay],
  );
}

/**
 * Slice 4 — Step 4 (Banking & Settlement) settlement-panel editor.
 *
 * Owned by agent 4B.2. Composed into the step-4 page alongside
 * {@link BankAccountsSection}.
 *
 * Wire shape sent on save (PATCH /api/v1/admin/partners/draft/{code}/step-4-settlement):
 *   { cycleTPlusN, cutoffTime, cutoffTimezone, settlementMethod }
 *
 * Live preview: on every field change (debounced 600ms) calls
 *   GET /settlement-preview?txnInstant=<now>
 * and renders the payoutDate + explanation trail.
 *
 * NOTE (deferred to Slice 8): The 2-authorized-signatory approval flow for
 * POST-ACTIVATION settlement-config changes is not implemented here. During
 * onboarding, writes go direct (audited). The approval gate will be added in
 * Slice 8 alongside the partner-lifecycle FSM.
 *
 * @param {object}   props
 * @param {object}   props.draft       PartnerView the wizard is editing.
 * @param {string}   props.partnerCode URL-pinned identifier.
 * @param {Function} [props.onSaved]   Called with updated PartnerView on success.
 */
export default function SettlementPanel({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();

  const { configByCode, previewByCode, configLoading, previewLoading, patchSaving } =
    useAppSelector((s) => s.settlementConfig);

  const savedConfig = configByCode[partnerCode] ?? null;
  const preview = previewByCode[partnerCode] ?? null;
  const isConfigLoading = configLoading[partnerCode] ?? false;
  const isPreviewLoading = previewLoading[partnerCode] ?? false;

  const {
    control,
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    defaultValues: DEFAULT_VALUES,
    mode: 'onBlur',
  });

  // Fetch saved config on mount.
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchSettlementConfig(partnerCode)).catch(() => {
        // Non-fatal: new drafts have no config yet.
      });
    }
  }, [partnerCode, dispatch]);

  // Re-populate form when saved config arrives.
  useEffect(() => {
    if (savedConfig) {
      reset({
        cycleTPlusN: savedConfig.cycleTPlusN ?? 1,
        cutoffTime: savedConfig.cutoffTime ?? '17:00',
        cutoffTimezone: savedConfig.cutoffTimezone ?? 'Asia/Seoul',
        settlementMethod: savedConfig.settlementMethod ?? 'SWIFT',
      });
    }
  }, [savedConfig, reset]);

  // Debounced preview refresh — triggers on any field change.
  const refreshPreview = useCallback(
    (values) => {
      if (!partnerCode) return;
      const txnInstant = new Date().toISOString();
      dispatch(fetchSettlementPreview({ partnerCode, txnInstant })).catch(() => {
        // Non-fatal best-effort UI chrome.
      });
    },
    [partnerCode, dispatch],
  );

  const debouncedRefresh = useDebounce(refreshPreview, 600);

  // Watch all fields and trigger preview on change.
  const watchedValues = watch();
  const prevValuesRef = useRef(null);

  useEffect(() => {
    const serialized = JSON.stringify(watchedValues);
    if (prevValuesRef.current !== serialized) {
      prevValuesRef.current = serialized;
      debouncedRefresh(watchedValues);
    }
  }, [watchedValues, debouncedRefresh]);

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    const body = {
      cycleTPlusN: Number(values.cycleTPlusN),
      cutoffTime: values.cutoffTime.trim(),
      cutoffTimezone: values.cutoffTimezone.trim(),
      settlementMethod: values.settlementMethod,
    };
    try {
      const result = await dispatch(
        patchDraftStep4Settlement({ partnerCode, body }),
      ).unwrap();
      snackbar.success(`Settlement config saved for ${partnerCode}`);
      if (typeof onSaved === 'function') onSaved(result);
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  const busy = patchSaving || isSubmitting;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="settlement-panel"
    >
      <Stack spacing={3}>
        {/* Header */}
        <Box>
          <Typography variant="h6" gutterBottom>
            Settlement Configuration
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Configure the settlement cycle, cutoff time, timezone, and settlement
            method for this partner. The live preview below shows the expected
            payout date for a transaction submitted right now.
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            <strong>Note:</strong> Post-activation changes require dual
            authorisation (deferred to Slice 8 — not yet enforced).
          </Typography>
        </Box>

        {isConfigLoading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
            <CircularProgress size={24} aria-label="loading-settlement-config" />
          </Box>
        )}

        {/* Settlement cycle */}
        <Controller
          name="cycleTPlusN"
          control={control}
          render={({ field }) => (
            <FormControl fullWidth error={!!errors?.cycleTPlusN}>
              <InputLabel id="cycle-label">Settlement cycle</InputLabel>
              <Select
                {...field}
                labelId="cycle-label"
                label="Settlement cycle"
                inputProps={{ 'aria-label': 'cycleTPlusN' }}
              >
                {CYCLE_OPTIONS.map((n) => (
                  <MenuItem key={n} value={n}>
                    T+{n}{n === 0 ? ' (same day)' : ''}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText>
                {errors?.cycleTPlusN?.message ??
                  'Number of business days after transaction date'}
              </FormHelperText>
            </FormControl>
          )}
        />

        {/* Cutoff time */}
        <TextField
          label="Cutoff time"
          type="time"
          fullWidth
          required
          {...register('cutoffTime', {
            required: 'Cutoff time is required',
            pattern: {
              value: /^\d{2}:\d{2}$/,
              message: 'Use HH:mm format (e.g. 17:00)',
            },
          })}
          error={!!errors?.cutoffTime}
          helperText={
            errors?.cutoffTime?.message ??
            'Transactions submitted after this time count as next-day'
          }
          InputLabelProps={{ shrink: true }}
          inputProps={{ 'aria-label': 'cutoffTime', step: 60 }}
        />

        {/* Timezone */}
        <Controller
          name="cutoffTimezone"
          control={control}
          rules={{ required: 'Timezone is required' }}
          render={({ field }) => (
            <FormControl fullWidth error={!!errors?.cutoffTimezone} required>
              <InputLabel id="tz-label">Cutoff timezone</InputLabel>
              <Select
                {...field}
                labelId="tz-label"
                label="Cutoff timezone"
                inputProps={{ 'aria-label': 'cutoffTimezone' }}
              >
                {ASIA_TIMEZONES.map((tz) => (
                  <MenuItem key={tz} value={tz}>
                    {tz}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText>
                {errors?.cutoffTimezone?.message ?? 'IANA timezone for cutoff evaluation'}
              </FormHelperText>
            </FormControl>
          )}
        />

        {/* Settlement method */}
        <Controller
          name="settlementMethod"
          control={control}
          rules={{ required: 'Settlement method is required' }}
          render={({ field }) => (
            <FormControl fullWidth error={!!errors?.settlementMethod} required>
              <InputLabel id="method-label">Settlement method</InputLabel>
              <Select
                {...field}
                labelId="method-label"
                label="Settlement method"
                inputProps={{ 'aria-label': 'settlementMethod' }}
              >
                {SETTLEMENT_METHODS.map((m) => (
                  <MenuItem key={m} value={m}>
                    {SETTLEMENT_METHOD_LABELS[m] ?? m}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText>
                {errors?.settlementMethod?.message ??
                  'Rail used to transfer funds to the partner'}
              </FormHelperText>
            </FormControl>
          )}
        />

        {/* Live preview box */}
        <PreviewBox preview={preview} loading={isPreviewLoading} />

        {/* Submit */}
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Box
            component="button"
            type="submit"
            disabled={busy}
            aria-label="save-settlement-config"
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 1,
              px: 3,
              py: 1,
              bgcolor: 'primary.main',
              color: 'primary.contrastText',
              border: 'none',
              borderRadius: 1,
              cursor: busy ? 'not-allowed' : 'pointer',
              opacity: busy ? 0.6 : 1,
              fontSize: '0.875rem',
              fontWeight: 500,
              '&:hover:not(:disabled)': { bgcolor: 'primary.dark' },
            }}
          >
            {busy && <CircularProgress size={14} color="inherit" />}
            Save settlement config
          </Box>
        </Box>
      </Stack>
    </Box>
  );
}

/**
 * Live payout preview box.
 *
 * @param {object} props
 * @param {object|null} props.preview  SettlementPreviewView or null.
 * @param {boolean}     props.loading  Preview fetch in-flight.
 */
function PreviewBox({ preview, loading }) {
  return (
    <Box
      aria-label="settlement-preview"
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        p: 2,
        bgcolor: 'background.default',
        minHeight: 80,
      }}
    >
      <Typography variant="subtitle2" gutterBottom>
        Live payout preview
      </Typography>

      {loading && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1 }}>
          <CircularProgress size={16} aria-label="preview-loading" />
          <Typography variant="body2" color="text.secondary">
            Calculating…
          </Typography>
        </Box>
      )}

      {!loading && !preview && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Adjust the fields above to see when a transaction right now would be
          settled.
        </Typography>
      )}

      {!loading && preview && (
        <Stack spacing={1} sx={{ mt: 1 }}>
          <Typography variant="body1" aria-label="preview-payout-date">
            A transaction right now would pay out on{' '}
            <strong>{preview.payoutDate}</strong>.
          </Typography>

          {Array.isArray(preview.explanation) && preview.explanation.length > 0 && (
            <>
              <Divider />
              <Typography variant="caption" color="text.secondary">
                Settlement trail
              </Typography>
              <ExplanationTimeline items={preview.explanation} />
            </>
          )}
        </Stack>
      )}
    </Box>
  );
}

/**
 * Renders the explanation trail from the preview as a compact vertical
 * timeline. Each entry is one string line (e.g. "Sat: skip — weekend",
 * "Mon: payout date").
 *
 * @param {object}   props
 * @param {string[]} props.items  Explanation lines from SettlementPreviewView.
 */
function ExplanationTimeline({ items }) {
  return (
    <Box
      component="ol"
      aria-label="explanation-trail"
      sx={{ m: 0, p: 0, listStyle: 'none' }}
    >
      {items.map((line, idx) => {
        const isLast = idx === items.length - 1;
        const isPayout = /payout/i.test(line);
        return (
          <Box
            key={idx}
            component="li"
            sx={{ display: 'flex', alignItems: 'flex-start', gap: 1, mb: 0.5 }}
            aria-label={`explanation-item-${idx}`}
          >
            <Box
              sx={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                flexShrink: 0,
                mt: '6px',
                bgcolor: isPayout ? 'success.main' : 'text.disabled',
              }}
            />
            <Typography
              variant="body2"
              color={isPayout ? 'success.main' : 'text.secondary'}
              fontWeight={isPayout ? 600 : 400}
            >
              {line}
            </Typography>
          </Box>
        );
      })}
    </Box>
  );
}

'use client';

import { useEffect, useMemo } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Autocomplete,
  Box,
  Button,
  CircularProgress,
  Divider,
  FormControl,
  FormControlLabel,
  FormHelperText,
  Grid,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep1 } from '@/store/draftsSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import partnerStep1Schema, {
  TAX_ID_PATTERNS,
} from '@/schemas/partnerStep1Schema';
import {
  TAX_ID_TYPES,
  TAX_ID_TYPE_LABELS,
  LEGAL_FORMS,
  LEGAL_FORM_LABELS,
  ISO_3166_ALPHA2,
  COUNTRY_LABELS,
} from '@/api/identityConstants';

/**
 * Slice 1 — Step 1 (Identity) form. Mounted by the wizard shell at
 * /partners/draft/{partnerCode}/step-1 once a draft has been created.
 *
 * Wire shape (sent on submit, matches BFF DraftPartnerStep1Request):
 *   {
 *     legalNameLocal, legalNameRomanized,
 *     taxIdType, taxId,
 *     countryOfIncorporation, legalForm,
 *     registeredAddress: { street1, street2?, city, state?, postcode, country },
 *     operatingAddress:  same shape (mirror of registered if "same as" toggled),
 *     lei: optional,
 *     // type / settlementCurrency / settlementRoundingMode are *preserved* from
 *     // the existing draft so the bitemporal stamp tick on Step-1 save does not
 *     // wipe them. They live on the partner aggregate but belong logically to
 *     // Slice 6 (commercial terms) — the wizard does not expose them at Step 1.
 *   }
 *
 * Validation lives in {@code @/schemas/partnerStep1Schema} and mirrors
 * {@code PartnerValidator.java} on the server. The form surfaces field-level
 * errors inline; a server-side rejection is shown via the existing snackbar.
 *
 * Helper text on tax-id flips per selected type so the operator sees the
 * format the schema is enforcing before submission.
 *
 * On successful PATCH, calls {@link onSaved} with the updated PartnerView
 * (the bitemporal recorded_at refreshes server-side) so the parent wizard
 * can advance the cursor.
 */

const TAX_ID_HELPER = {
  KR_BRN: '10 digits, no dashes (e.g. 1234567890)',
  KH_VAT: '10 digits, no dashes',
  VN_MST: '10 or 13 digits (13 = branch suffix)',
  SG_UEN: 'ACRA UEN: 8-9 alphanumerics + trailing letter (e.g. 201712345A)',
  GENERIC: 'Any non-blank string',
};

/** Empty structured address used when the form initialises a blank draft. */
function emptyAddress() {
  return { street1: '', street2: '', city: '', state: '', postcode: '', country: '' };
}

/**
 * Read the address from the draft view; the BFF returns null when no address
 * has been saved yet, but RHF needs every field defined so the controlled
 * inputs stay controlled across renders.
 */
function addressFromView(view) {
  if (!view) return emptyAddress();
  return {
    street1: view.street1 ?? '',
    street2: view.street2 ?? '',
    city: view.city ?? '',
    state: view.state ?? '',
    postcode: view.postcode ?? '',
    country: view.country ?? '',
  };
}

/** Are two addresses pointwise equal (ignoring leading/trailing whitespace)? */
function addressEquals(a, b) {
  if (!a || !b) return false;
  const fields = ['street1', 'street2', 'city', 'state', 'postcode', 'country'];
  return fields.every((f) => (a[f] ?? '').trim() === (b[f] ?? '').trim());
}

/** Build initial form values from the loaded draft, with safe defaults. */
function defaultsFromDraft(draft) {
  const registered = addressFromView(draft?.registeredAddress);
  const operating = addressFromView(draft?.operatingAddress);
  return {
    partnerCode: draft?.partnerCode ?? '',
    legalNameLocal: draft?.legalNameLocal ?? '',
    legalNameRomanized: draft?.legalNameRomanized ?? '',
    taxIdType: draft?.taxIdType ?? 'KR_BRN',
    taxId: draft?.taxId ?? '',
    countryOfIncorporation: draft?.countryOfIncorporation ?? '',
    legalForm: draft?.legalForm ?? 'CORP',
    registeredAddress: registered,
    operatingSameAsRegistered: addressEquals(registered, operating),
    operatingAddress: operating,
    lei: draft?.lei ?? '',
  };
}

/** Build the PATCH body the BFF expects (DraftPartnerStep1Request). */
function bodyFromValues(values, draft) {
  const operating = values.operatingSameAsRegistered
    ? { ...values.registeredAddress }
    : values.operatingAddress;
  return {
    // Preserve commercial-terms fields the wizard does not expose at Step 1.
    type: draft?.type ?? null,
    settlementCurrency: draft?.settlementCurrency ?? null,
    settlementRoundingMode: draft?.settlementRoundingMode ?? null,
    // Identity fields ↓
    legalNameLocal: values.legalNameLocal.trim(),
    legalNameRomanized: values.legalNameRomanized.trim(),
    taxIdType: values.taxIdType,
    taxId: values.taxId.trim(),
    countryOfIncorporation: values.countryOfIncorporation.toUpperCase(),
    legalForm: values.legalForm,
    registeredAddress: normaliseAddress(values.registeredAddress),
    operatingAddress: normaliseAddress(operating),
    lei: values.lei && values.lei.trim() !== '' ? values.lei.trim().toUpperCase() : null,
  };
}

function normaliseAddress(addr) {
  if (!addr) return null;
  return {
    street1: (addr.street1 ?? '').trim(),
    street2: addr.street2 && addr.street2.trim() !== '' ? addr.street2.trim() : null,
    city: (addr.city ?? '').trim(),
    state: addr.state && addr.state.trim() !== '' ? addr.state.trim() : null,
    postcode: (addr.postcode ?? '').trim(),
    country: (addr.country ?? '').toUpperCase(),
  };
}

/** Render label for the country picker — "KR — Korea, Republic of" etc. */
function countryLabel(code) {
  if (!code) return '';
  const name = COUNTRY_LABELS[code];
  return name ? `${code} — ${name}` : code;
}

/**
 * Slice 1 Identity form.
 *
 * @param {object} props
 * @param {object} props.draft     PartnerView the wizard is editing.
 * @param {string} props.partnerCode  URL-pinned identifier; used for the PATCH.
 * @param {(view:object)=>void} [props.onSaved]  Called on successful PATCH.
 */
export default function IdentityForm({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { saving } = useAppSelector((s) => s.drafts);

  const defaults = useMemo(() => defaultsFromDraft(draft), [draft]);

  const {
    control,
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(partnerStep1Schema),
    defaultValues: defaults,
    mode: 'onBlur',
  });

  // When the draft prop refreshes (e.g. after fetchDraft), repopulate the
  // form. Without this the form would freeze on its initial values even when
  // the BFF returned more recent server-side state.
  useEffect(() => {
    reset(defaults);
  }, [defaults, reset]);

  const taxIdType = watch('taxIdType');
  const operatingSame = watch('operatingSameAsRegistered');

  const taxIdHelper =
    errors.taxId?.message ?? TAX_ID_HELPER[taxIdType] ?? TAX_ID_HELPER.GENERIC;

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    const body = bodyFromValues(values, draft);
    try {
      const result = await dispatch(
        patchStep1({ partnerCode, body }),
      ).unwrap();
      snackbar.success(`Step 1 saved for ${partnerCode}`);
      if (typeof onSaved === 'function') onSaved(result);
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  const busy = saving || isSubmitting;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="partner-identity-form"
    >
      <Stack spacing={3}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Identity
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 1 of the partner setup wizard. Fields below match the partner
            aggregate&apos;s bitemporal Identity columns; the BFF re-validates
            every value before persisting.
          </Typography>
        </Box>

        <TextField
          label="Partner code"
          fullWidth
          required
          disabled
          {...register('partnerCode')}
          inputProps={{ 'aria-label': 'Partner code', readOnly: true }}
          helperText="Set when the draft was created. Becomes the partner's permanent business key on activation."
        />

        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <TextField
              label="Legal name (local script)"
              fullWidth
              required
              {...register('legalNameLocal')}
              error={!!errors.legalNameLocal}
              helperText={
                errors.legalNameLocal?.message ??
                'As shown on the registration certificate (Hangul, Khmer, etc.).'
              }
              inputProps={{ 'aria-label': 'Legal name local' }}
            />
          </Grid>
          <Grid item xs={12} md={6}>
            <TextField
              label="Legal name (romanized)"
              fullWidth
              required
              {...register('legalNameRomanized')}
              error={!!errors.legalNameRomanized}
              helperText={
                errors.legalNameRomanized?.message ??
                'Latin-script equivalent for SWIFT, Travel Rule, and BOK filings.'
              }
              inputProps={{ 'aria-label': 'Legal name romanized' }}
            />
          </Grid>
        </Grid>

        <Grid container spacing={2}>
          <Grid item xs={12} md={5}>
            <Controller
              name="taxIdType"
              control={control}
              render={({ field }) => (
                <FormControl fullWidth error={!!errors.taxIdType} required>
                  <InputLabel id="tax-id-type-label">Tax-id type</InputLabel>
                  <Select
                    {...field}
                    labelId="tax-id-type-label"
                    label="Tax-id type"
                    id="tax-id-type"
                  >
                    {TAX_ID_TYPES.map((t) => (
                      <MenuItem key={t} value={t}>
                        {TAX_ID_TYPE_LABELS[t] ?? t}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors.taxIdType?.message ??
                      'Picks the format rule applied to the tax id below.'}
                  </FormHelperText>
                </FormControl>
              )}
            />
          </Grid>
          <Grid item xs={12} md={7}>
            <TextField
              label="Tax id"
              fullWidth
              required
              {...register('taxId')}
              error={!!errors.taxId}
              helperText={taxIdHelper}
              inputProps={{ 'aria-label': 'Tax id' }}
            />
          </Grid>
        </Grid>

        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <Controller
              name="countryOfIncorporation"
              control={control}
              render={({ field, fieldState }) => (
                <Autocomplete
                  options={ISO_3166_ALPHA2}
                  value={field.value || null}
                  onChange={(_e, value) => field.onChange(value ?? '')}
                  getOptionLabel={countryLabel}
                  isOptionEqualToValue={(opt, value) => opt === value}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Country of incorporation"
                      required
                      error={!!fieldState.error}
                      helperText={
                        fieldState.error?.message ??
                        'ISO-3166 alpha-2 (e.g. KR, KH, VN, SG).'
                      }
                      inputProps={{
                        ...params.inputProps,
                        'aria-label': 'Country of incorporation',
                      }}
                    />
                  )}
                />
              )}
            />
          </Grid>
          <Grid item xs={12} md={6}>
            <Controller
              name="legalForm"
              control={control}
              render={({ field }) => (
                <FormControl fullWidth error={!!errors.legalForm} required>
                  <InputLabel id="legal-form-label">Legal form</InputLabel>
                  <Select
                    {...field}
                    labelId="legal-form-label"
                    label="Legal form"
                    id="legal-form"
                  >
                    {LEGAL_FORMS.map((f) => (
                      <MenuItem key={f} value={f}>
                        {LEGAL_FORM_LABELS[f] ?? f}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors.legalForm?.message ??
                      'Corporate type as registered with the regulator.'}
                  </FormHelperText>
                </FormControl>
              )}
            />
          </Grid>
        </Grid>

        <Divider textAlign="left">
          <Typography variant="overline">Registered address</Typography>
        </Divider>

        <AddressFields
          prefix="registeredAddress"
          register={register}
          control={control}
          errors={errors?.registeredAddress}
          labelHint="Address on the registration certificate."
        />

        <Divider textAlign="left">
          <Typography variant="overline">Operating address</Typography>
        </Divider>

        <Controller
          name="operatingSameAsRegistered"
          control={control}
          render={({ field }) => (
            <FormControlLabel
              control={
                <Switch
                  checked={!!field.value}
                  onChange={(e) => field.onChange(e.target.checked)}
                  inputProps={{ 'aria-label': 'Operating address same as registered' }}
                />
              }
              label="Operating address same as registered"
            />
          )}
        />

        {!operatingSame ? (
          <AddressFields
            prefix="operatingAddress"
            register={register}
            control={control}
            errors={errors?.operatingAddress}
            labelHint="Day-to-day business address (if different from registered)."
          />
        ) : null}

        <TextField
          label="LEI (optional)"
          fullWidth
          {...register('lei')}
          error={!!errors.lei}
          helperText={
            errors.lei?.message ??
            'ISO 17442 Legal Entity Identifier (20 alphanumerics, mod-97-10 checksum). Leave blank if not registered.'
          }
          inputProps={{
            'aria-label': 'LEI',
            maxLength: 20,
            style: { textTransform: 'uppercase' },
          }}
        />

        <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            Save &amp; next
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}

/**
 * Address sub-form — one column-of-fields block. {@code prefix} is the RHF
 * field-name root (e.g. {@code "registeredAddress"}); the country picker is
 * rendered via a Controller so the Autocomplete value tracks RHF state.
 */
function AddressFields({ prefix, register, control, errors, labelHint }) {
  return (
    <Box>
      {labelHint ? (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {labelHint}
        </Typography>
      ) : null}
      <Grid container spacing={2}>
        <Grid item xs={12} md={8}>
          <TextField
            label="Street line 1"
            fullWidth
            required
            {...register(`${prefix}.street1`)}
            error={!!errors?.street1}
            helperText={errors?.street1?.message ?? ' '}
            inputProps={{ 'aria-label': `${prefix} street1` }}
          />
        </Grid>
        <Grid item xs={12} md={4}>
          <TextField
            label="Street line 2"
            fullWidth
            {...register(`${prefix}.street2`)}
            error={!!errors?.street2}
            helperText={errors?.street2?.message ?? 'Unit, floor, etc. (optional)'}
            inputProps={{ 'aria-label': `${prefix} street2` }}
          />
        </Grid>
        <Grid item xs={12} md={5}>
          <TextField
            label="City"
            fullWidth
            required
            {...register(`${prefix}.city`)}
            error={!!errors?.city}
            helperText={errors?.city?.message ?? ' '}
            inputProps={{ 'aria-label': `${prefix} city` }}
          />
        </Grid>
        <Grid item xs={12} md={4}>
          <TextField
            label="State / province"
            fullWidth
            {...register(`${prefix}.state`)}
            error={!!errors?.state}
            helperText={errors?.state?.message ?? 'Optional in many jurisdictions.'}
            inputProps={{ 'aria-label': `${prefix} state` }}
          />
        </Grid>
        <Grid item xs={12} md={3}>
          <TextField
            label="Postcode"
            fullWidth
            required
            {...register(`${prefix}.postcode`)}
            error={!!errors?.postcode}
            helperText={errors?.postcode?.message ?? ' '}
            inputProps={{ 'aria-label': `${prefix} postcode` }}
          />
        </Grid>
        <Grid item xs={12} md={12}>
          <Controller
            name={`${prefix}.country`}
            control={control}
            render={({ field, fieldState }) => (
              <Autocomplete
                options={ISO_3166_ALPHA2}
                value={field.value || null}
                onChange={(_e, value) => field.onChange(value ?? '')}
                getOptionLabel={countryLabel}
                isOptionEqualToValue={(opt, value) => opt === value}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label="Country"
                    required
                    error={!!fieldState.error}
                    helperText={
                      fieldState.error?.message ??
                      'ISO-3166 alpha-2 (e.g. KR).'
                    }
                    inputProps={{
                      ...params.inputProps,
                      'aria-label': `${prefix} country`,
                    }}
                  />
                )}
              />
            )}
          />
        </Grid>
      </Grid>
    </Box>
  );
}

// Re-export the tax-id patterns so the parent wizard / future steps can
// reuse the same regexes for read-only displays without parsing them out
// of the schema.
export { TAX_ID_PATTERNS };

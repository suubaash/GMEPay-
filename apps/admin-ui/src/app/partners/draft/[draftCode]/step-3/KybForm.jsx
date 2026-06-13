'use client';

import { useEffect, useMemo, useState } from 'react';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Divider,
  FormControl,
  FormControlLabel,
  FormHelperText,
  Grid,
  IconButton,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import SearchIcon from '@mui/icons-material/Search';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep3 } from '@/store/draftsSlice';
import { fetchKyb, runScreening } from '@/store/kybSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import DocumentVault from '@/components/DocumentVault';
import partnerStep3Schema, {
  RISK_RATINGS,
  RISK_RATING_LABELS,
} from '@/schemas/partnerStep3Schema';
import { ISO_3166_ALPHA2, COUNTRY_LABELS } from '@/api/identityConstants';

/**
 * Slice 3 — Step 3 (KYB) form. Mounted by the wizard shell at
 * /partners/draft/{partnerCode} when cursor === 3.
 *
 * Wire shape (sent on submit, matches BFF KybCommand.UpdateStep3):
 *   {
 *     riskRating: 'LOW'|'MEDIUM'|'HIGH',
 *     riskRationale: string,
 *     nextReviewDate: string (YYYY-MM-DD),
 *     licenseType: string,
 *     licenseNumber: string,
 *     licenseAuthority: string,
 *     licenseExpiry: string (YYYY-MM-DD),
 *     uboList: [{ name, ownershipPct, isPep, country }],
 *     cbddqDocId: string|null,
 *   }
 *
 * Screening result panel: status chip (CLEAR=green / NEEDS_REVIEW=amber /
 * HIT=red), hits list, screened-at timestamp; powered by
 * {@link fetchKyb} + {@link runScreening} thunks in kybSlice.
 *
 * @param {object} props
 * @param {object} props.draft       PartnerView the wizard is editing.
 * @param {string} props.partnerCode URL-pinned identifier; used for the PATCH.
 * @param {(view:object)=>void} [props.onSaved] Called on successful PATCH.
 */
export default function KybForm({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { saving } = useAppSelector((s) => s.drafts);
  const { kybByCode, kybLoading } = useAppSelector((s) => s.kyb);
  const kyb = kybByCode[partnerCode] ?? null;

  // cbddqDocId managed outside RHF so DocumentVault can update it.
  const [cbddqDocId, setCbddqDocId] = useState(kyb?.cbddqDocId ?? null);

  const defaults = useMemo(() => defaultsFromKyb(kyb), [kyb]);

  const {
    control,
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(partnerStep3Schema),
    defaultValues: defaults,
    mode: 'onBlur',
  });

  const { fields, append, remove } = useFieldArray({
    control,
    name: 'uboList',
  });

  // Fetch existing KYB data when the form mounts (returns existing data or
  // 404 → handled gracefully; the form starts blank on 404).
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchKyb(partnerCode)).catch(() => {
        // 404 is expected for brand-new drafts — form starts blank.
      });
    }
  }, [partnerCode, dispatch]);

  // Repopulate when KYB data arrives.
  useEffect(() => {
    reset(defaultsFromKyb(kyb));
    setCbddqDocId(kyb?.cbddqDocId ?? null);
  }, [kyb, reset]);

  const uboList = watch('uboList') ?? [];

  // Soft warning: total ownership > 100%.
  const ownershipSum = uboList.reduce((acc, u) => {
    const pct = parseFloat(u?.ownershipPct);
    return acc + (Number.isFinite(pct) ? pct : 0);
  }, 0);
  const showOwnershipWarning = ownershipSum > 100;

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    const body = {
      riskRating: values.riskRating,
      riskRationale: values.riskRationale?.trim() ?? '',
      nextReviewDate: values.nextReviewDate,
      licenseType: values.licenseType.trim(),
      licenseNumber: values.licenseNumber.trim(),
      licenseAuthority: values.licenseAuthority.trim(),
      licenseExpiry: values.licenseExpiry,
      uboList: values.uboList.map((u) => ({
        name: u.name.trim(),
        ownershipPct: Number(u.ownershipPct),
        isPep: !!u.isPep,
        country: u.country,
      })),
      cbddqDocId: cbddqDocId ?? null,
    };
    try {
      const result = await dispatch(patchStep3({ partnerCode, body })).unwrap();
      snackbar.success(`Step 3 saved for ${partnerCode}`);
      if (typeof onSaved === 'function') onSaved(result);
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  const handleRunScreening = async () => {
    if (!partnerCode) return;
    try {
      await dispatch(runScreening(partnerCode)).unwrap();
      snackbar.success('Screening complete');
    } catch (e) {
      const message = e?.message ?? 'Screening request failed';
      snackbar.error(message);
    }
  };

  const busy = saving || isSubmitting;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="partner-kyb-form"
    >
      <Stack spacing={4}>
        {/* Header */}
        <Box>
          <Typography variant="h6" gutterBottom>
            KYB — Know Your Business
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 3 of the partner setup wizard. Complete the regulatory
            licence, beneficial-ownership, and risk-rating information. Screening
            is powered by the KybProvider port (ADR-009); the Octa Solution
            integration is stubbed until sandbox credentials arrive (ADR-014).
          </Typography>
        </Box>

        {/* ── License section ── */}
        <Box>
          <Divider textAlign="left" sx={{ mb: 2 }}>
            <Typography variant="overline">Regulatory Licence</Typography>
          </Divider>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField
                label="License type"
                fullWidth
                required
                {...register('licenseType')}
                error={!!errors.licenseType}
                helperText={errors.licenseType?.message ?? 'e.g. Money Transfer Operator, PSP'}
                inputProps={{ 'aria-label': 'licenseType' }}
              />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                label="License number"
                fullWidth
                required
                {...register('licenseNumber')}
                error={!!errors.licenseNumber}
                helperText={errors.licenseNumber?.message ?? 'Regulator-issued reference number'}
                inputProps={{ 'aria-label': 'licenseNumber' }}
              />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                label="Issuing authority"
                fullWidth
                required
                {...register('licenseAuthority')}
                error={!!errors.licenseAuthority}
                helperText={errors.licenseAuthority?.message ?? 'e.g. FSC Korea, MAS Singapore'}
                inputProps={{ 'aria-label': 'licenseAuthority' }}
              />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                label="License expiry"
                fullWidth
                required
                type="date"
                {...register('licenseExpiry')}
                error={!!errors.licenseExpiry}
                helperText={errors.licenseExpiry?.message ?? 'YYYY-MM-DD'}
                InputLabelProps={{ shrink: true }}
                inputProps={{ 'aria-label': 'licenseExpiry' }}
              />
            </Grid>
          </Grid>
        </Box>

        {/* ── UBO table ── */}
        <Box>
          <Divider textAlign="left" sx={{ mb: 2 }}>
            <Typography variant="overline">Ultimate Beneficial Owners (UBO)</Typography>
          </Divider>

          {showOwnershipWarning && (
            <Alert
              severity="warning"
              variant="outlined"
              sx={{ mb: 2 }}
              aria-label="ownership-sum-warning"
            >
              Total ownership percentage is {ownershipSum.toFixed(1)}% which exceeds 100%.
              This is allowed for complex holding structures — verify with the partner.
            </Alert>
          )}

          {fields.length === 0 && (
            <Alert severity="warning" variant="outlined" sx={{ mb: 2 }}>
              No UBOs added yet. Click &ldquo;Add UBO&rdquo; to add the first entry.
            </Alert>
          )}

          {fields.map((field, index) => (
            <UboRow
              key={field.id}
              index={index}
              control={control}
              register={register}
              errors={errors?.uboList?.[index]}
              onRemove={() => remove(index)}
              removable={fields.length > 1}
            />
          ))}

          {typeof errors?.uboList?.message === 'string' && (
            <Typography variant="body2" color="error" role="alert">
              {errors.uboList.message}
            </Typography>
          )}

          <Button
            type="button"
            variant="outlined"
            startIcon={<AddIcon />}
            onClick={() =>
              append({
                name: '',
                ownershipPct: '',
                isPep: false,
                country: '',
              })
            }
            aria-label="Add UBO"
          >
            Add UBO
          </Button>
        </Box>

        {/* ── Risk rating ── */}
        <Box>
          <Divider textAlign="left" sx={{ mb: 2 }}>
            <Typography variant="overline">Risk Assessment</Typography>
          </Divider>
          <Grid container spacing={2}>
            <Grid item xs={12} md={4}>
              <Controller
                name="riskRating"
                control={control}
                render={({ field }) => (
                  <FormControl fullWidth error={!!errors.riskRating} required>
                    <InputLabel id="risk-rating-label">Risk rating</InputLabel>
                    <Select
                      {...field}
                      labelId="risk-rating-label"
                      label="Risk rating"
                      inputProps={{ 'aria-label': 'riskRating' }}
                    >
                      {RISK_RATINGS.map((r) => (
                        <MenuItem key={r} value={r}>
                          {RISK_RATING_LABELS[r]}
                        </MenuItem>
                      ))}
                    </Select>
                    <FormHelperText>
                      {errors.riskRating?.message ?? 'Overall AML/CFT risk classification'}
                    </FormHelperText>
                  </FormControl>
                )}
              />
            </Grid>
            <Grid item xs={12} md={8}>
              <TextField
                label="Risk rationale"
                fullWidth
                required
                multiline
                minRows={3}
                {...register('riskRationale')}
                error={!!errors.riskRationale}
                helperText={
                  errors.riskRationale?.message ??
                  'Justify the rating with reference to the jurisdiction, business type, and volume.'
                }
                inputProps={{ 'aria-label': 'riskRationale' }}
              />
            </Grid>
            <Grid item xs={12} md={4}>
              <TextField
                label="Next review date"
                fullWidth
                required
                type="date"
                {...register('nextReviewDate')}
                error={!!errors.nextReviewDate}
                helperText={errors.nextReviewDate?.message ?? 'Scheduled next KYB review date'}
                InputLabelProps={{ shrink: true }}
                inputProps={{ 'aria-label': 'nextReviewDate' }}
              />
            </Grid>
          </Grid>
        </Box>

        {/* ── Screening panel ── */}
        <Box>
          <Divider textAlign="left" sx={{ mb: 2 }}>
            <Typography variant="overline">AML / PEP Screening</Typography>
          </Divider>

          <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2 }}>
            <Button
              type="button"
              variant="outlined"
              startIcon={
                kybLoading ? (
                  <CircularProgress size={16} color="inherit" />
                ) : (
                  <SearchIcon />
                )
              }
              onClick={handleRunScreening}
              disabled={kybLoading}
              aria-label="run-screening"
            >
              Run screening
            </Button>
            {kyb?.screenedAt && (
              <Typography variant="caption" color="text.secondary">
                Last screened: {new Date(kyb.screenedAt).toLocaleString()}
              </Typography>
            )}
          </Stack>

          {kyb?.screeningStatus && (
            <ScreeningResultPanel kyb={kyb} />
          )}
        </Box>

        {/* ── Document vault ── */}
        <Box>
          <Divider textAlign="left" sx={{ mb: 2 }}>
            <Typography variant="overline">Documents</Typography>
          </Divider>
          <Stack spacing={2}>
            <DocumentVault
              partnerCode={partnerCode}
              docType="License scan"
              onUploaded={(docId) => snackbar.info(`License doc uploaded: ${docId}`)}
            />
            <DocumentVault
              partnerCode={partnerCode}
              docType="CBDDQ"
              onUploaded={(docId) => {
                setCbddqDocId(docId);
                snackbar.info(`CBDDQ uploaded: ${docId}`);
              }}
            />
          </Stack>
        </Box>

        {/* ── Submit ── */}
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
 * Screening result panel — status chip + hits list.
 *
 * @param {object} props
 * @param {object} props.kyb KybView from the store.
 */
function ScreeningResultPanel({ kyb }) {
  const chipProps = SCREENING_CHIP_PROPS[kyb.screeningStatus] ?? {
    label: kyb.screeningStatus,
    color: 'default',
  };

  return (
    <Stack spacing={1} aria-label="screening-result-panel">
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          Status:
        </Typography>
        <Chip
          label={chipProps.label}
          color={chipProps.color}
          size="small"
          aria-label={`screening-status-${kyb.screeningStatus}`}
        />
        {kyb.screeningProviderRef && (
          <Typography variant="caption" color="text.secondary">
            Ref: {kyb.screeningProviderRef}
          </Typography>
        )}
      </Box>

      {Array.isArray(kyb.screeningHits) && kyb.screeningHits.length > 0 && (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
            Hits ({kyb.screeningHits.length}):
          </Typography>
          <Stack spacing={0.5} aria-label="screening-hits-list">
            {kyb.screeningHits.map((hit, i) => (
              <Box
                key={i}
                sx={{
                  p: 1,
                  borderRadius: 1,
                  bgcolor: 'action.hover',
                  display: 'flex',
                  gap: 1,
                  flexWrap: 'wrap',
                }}
              >
                <Typography variant="body2" sx={{ fontWeight: 500 }}>
                  {hit.name}
                </Typography>
                {hit.matchType && (
                  <Chip label={hit.matchType} size="small" variant="outlined" />
                )}
                {hit.source && (
                  <Typography variant="caption" color="text.secondary">
                    Source: {hit.source}
                  </Typography>
                )}
                {hit.matchScore != null && (
                  <Typography variant="caption" color="text.secondary">
                    Score: {hit.matchScore}
                  </Typography>
                )}
              </Box>
            ))}
          </Stack>
        </Box>
      )}
    </Stack>
  );
}

/**
 * Chip appearance by screening status.
 * CLEAR = green, NEEDS_REVIEW = amber, HIT = red.
 */
const SCREENING_CHIP_PROPS = {
  CLEAR: { label: 'Clear', color: 'success' },
  NEEDS_REVIEW: { label: 'Needs review', color: 'warning' },
  HIT: { label: 'Hit', color: 'error' },
};

/**
 * A single UBO row with name, ownership %, PEP toggle, and country.
 */
function UboRow({ index, control, register, errors, onRemove, removable }) {
  const prefix = `uboList.${index}`;

  return (
    <Box sx={{ mb: 2 }}>
      <Divider textAlign="left" sx={{ mb: 2 }}>
        <Typography variant="overline">UBO {index + 1}</Typography>
      </Divider>

      <Grid container spacing={2} alignItems="flex-start">
        {/* Name */}
        <Grid item xs={12} md={4}>
          <TextField
            label="Full name"
            fullWidth
            required
            {...register(`${prefix}.name`)}
            error={!!errors?.name}
            helperText={errors?.name?.message ?? 'Legal name of the beneficial owner'}
            inputProps={{ 'aria-label': `uboList[${index}].name` }}
          />
        </Grid>

        {/* Ownership % */}
        <Grid item xs={12} md={2}>
          <TextField
            label="Ownership %"
            fullWidth
            required
            type="number"
            inputProps={{
              min: 0,
              max: 100,
              step: 0.01,
              'aria-label': `uboList[${index}].ownershipPct`,
            }}
            {...register(`${prefix}.ownershipPct`)}
            error={!!errors?.ownershipPct}
            helperText={errors?.ownershipPct?.message ?? '0–100'}
          />
        </Grid>

        {/* Country */}
        <Grid item xs={12} md={3}>
          <Controller
            name={`${prefix}.country`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.country} required>
                <InputLabel id={`country-label-${index}`}>Country</InputLabel>
                <Select
                  {...field}
                  labelId={`country-label-${index}`}
                  label="Country"
                  inputProps={{ 'aria-label': `uboList[${index}].country` }}
                >
                  {ISO_3166_ALPHA2.map((code) => (
                    <MenuItem key={code} value={code}>
                      {COUNTRY_LABELS[code] ?? code}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.country?.message ?? 'Country of residence'}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Grid>

        {/* PEP toggle */}
        <Grid item xs={12} md={2}>
          <Controller
            name={`${prefix}.isPep`}
            control={control}
            render={({ field }) => (
              <FormControlLabel
                control={
                  <Checkbox
                    checked={!!field.value}
                    onChange={(e) => field.onChange(e.target.checked)}
                    inputProps={{
                      'aria-label': `uboList[${index}].isPep`,
                    }}
                  />
                }
                label="PEP"
              />
            )}
          />
        </Grid>

        {/* Remove button */}
        <Grid item xs={12} md={1} sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Tooltip title={removable ? 'Remove UBO' : 'Cannot remove the only UBO'}>
            <span>
              <IconButton
                onClick={onRemove}
                disabled={!removable}
                color="error"
                aria-label={`remove-ubo-${index}`}
                size="small"
              >
                <DeleteOutlineIcon />
              </IconButton>
            </span>
          </Tooltip>
        </Grid>
      </Grid>
    </Box>
  );
}

/**
 * Build initial form values from the KYB view loaded from the BFF.
 * Returns sensible defaults when no KYB data exists yet (new draft).
 */
function defaultsFromKyb(kyb) {
  if (!kyb) {
    return {
      licenseType: '',
      licenseNumber: '',
      licenseAuthority: '',
      licenseExpiry: '',
      uboList: [{ name: '', ownershipPct: '', isPep: false, country: '' }],
      riskRating: '',
      riskRationale: '',
      nextReviewDate: '',
      cbddqDocId: '',
    };
  }
  return {
    licenseType: kyb.licenseType ?? '',
    licenseNumber: kyb.licenseNumber ?? '',
    licenseAuthority: kyb.licenseAuthority ?? '',
    licenseExpiry: kyb.licenseExpiry ?? '',
    uboList:
      Array.isArray(kyb.uboList) && kyb.uboList.length > 0
        ? kyb.uboList.map((u) => ({
            name: u.name ?? '',
            ownershipPct: u.ownershipPct ?? '',
            isPep: !!u.isPep,
            country: u.country ?? '',
          }))
        : [{ name: '', ownershipPct: '', isPep: false, country: '' }],
    riskRating: kyb.riskRating ?? '',
    riskRationale: kyb.riskRationale ?? '',
    nextReviewDate: kyb.nextReviewDate ?? '',
    cbddqDocId: kyb.cbddqDocId ?? '',
  };
}

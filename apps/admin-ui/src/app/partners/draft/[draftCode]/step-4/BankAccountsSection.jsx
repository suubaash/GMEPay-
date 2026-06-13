'use client';

import { useEffect, useMemo } from 'react';
import { Controller, useFieldArray, useForm } from 'react-hook-form';
import { yupResolver } from '@hookform/resolvers/yup';
import {
  Alert,
  Box,
  Button,
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
  Radio,
  RadioGroup,
  Select,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import VerifiedIcon from '@mui/icons-material/Verified';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep4 } from '@/store/draftsSlice';
import { fetchBankAccounts, verifyBankAccount } from '@/store/bankAccountsSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import partnerStep4BankSchema, {
  CHARGE_BEARERS,
  CHARGE_BEARER_LABELS,
  ACCOUNT_PURPOSES,
  ACCOUNT_PURPOSE_LABELS,
  VERIFICATION_STATUS_CONFIG,
} from '@/schemas/partnerStep4BankSchema';
import { ISO_3166_ALPHA2, COUNTRY_LABELS } from '@/api/identityConstants';

/**
 * Slice 4 — Step 4 (Banking & Settlement) bank-account editor.
 *
 * Owned by agent 4A.2. The settlement-panel section is owned by agent 4B.2
 * which composes this section into the wizard page.
 *
 * Wire shape sent on submit (PATCH /api/v1/admin/partners/draft/{code}/step-4):
 *   { bankAccounts: [BankAccountRequest] }
 *
 * BankAccountRequest fields:
 *   currency, bankName, bicSwift, ibanOrAccountNumber, accountHolderName,
 *   bankCountry, intermediaryBic?, swiftChargeBearer, purpose, isPrimary
 *
 * Verification chip colour mapping:
 *   UNVERIFIED     → grey  (default)
 *   KFTC_VERIFIED  → green (success)
 *   BANK_LETTER    → blue  (info)
 *   MICRO_DEPOSIT  → amber (warning)
 *
 * NOTE (deferred to Slice 8): The 2-authorized-signatory approval flow for
 * POST-ACTIVATION bank-account changes is not implemented here. During
 * onboarding, writes go direct (audited). The approval gate will be added in
 * Slice 8 alongside the partner-lifecycle FSM.
 *
 * @param {object}   props
 * @param {object}   props.draft       PartnerView the wizard is editing.
 * @param {string}   props.partnerCode URL-pinned identifier used for PATCH/GET.
 * @param {Function} [props.onSaved]   Called with updated PartnerView on success.
 */
export default function BankAccountsSection({ draft, partnerCode, onSaved }) {
  const dispatch = useAppDispatch();
  const snackbar = useSnackbar();
  const { saving } = useAppSelector((s) => s.drafts);
  const { byCode, loading: loadingByCode, verifying } = useAppSelector((s) => s.bankAccounts);

  const savedAccounts = useMemo(() => byCode[partnerCode] ?? [], [byCode, partnerCode]);
  const loadingAccounts = loadingByCode[partnerCode] ?? false;

  const {
    control,
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: yupResolver(partnerStep4BankSchema),
    defaultValues: { bankAccounts: [emptyRow()] },
    mode: 'onBlur',
  });

  const { fields, append, remove } = useFieldArray({
    control,
    name: 'bankAccounts',
  });

  // Fetch saved bank accounts when the section mounts.
  useEffect(() => {
    if (partnerCode) {
      dispatch(fetchBankAccounts(partnerCode)).catch(() => {
        // Non-fatal — form starts with one empty row on 404.
      });
    }
  }, [partnerCode, dispatch]);

  // Re-populate the form when saved accounts arrive from the BFF.
  useEffect(() => {
    if (savedAccounts.length > 0) {
      reset({
        bankAccounts: savedAccounts.map((a) => ({
          currency: a.currency ?? '',
          bankName: a.bankName ?? '',
          bicSwift: a.bicSwift ?? '',
          ibanOrAccountNumber: a.ibanOrAccountNumber ?? '',
          accountHolderName: a.accountHolderName ?? '',
          bankCountry: a.bankCountry ?? '',
          intermediaryBic: a.intermediaryBic ?? '',
          swiftChargeBearer: a.swiftChargeBearer ?? 'SHA',
          purpose: a.purpose ?? 'PAYOUT',
          isPrimary: !!a.isPrimary,
        })),
      });
    }
  }, [savedAccounts, reset]);

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    const body = {
      bankAccounts: values.bankAccounts.map((a) => ({
        currency: a.currency.trim().toUpperCase(),
        bankName: a.bankName.trim(),
        bicSwift: a.bicSwift.trim().toUpperCase(),
        ibanOrAccountNumber: a.ibanOrAccountNumber.trim(),
        accountHolderName: a.accountHolderName.trim(),
        bankCountry: a.bankCountry.trim().toUpperCase(),
        intermediaryBic: a.intermediaryBic?.trim().toUpperCase() || null,
        swiftChargeBearer: a.swiftChargeBearer,
        purpose: a.purpose,
        isPrimary: !!a.isPrimary,
      })),
    };
    try {
      const result = await dispatch(patchStep4({ partnerCode, body })).unwrap();
      snackbar.success(`Step 4 saved for ${partnerCode}`);
      // Refresh the saved list to pick up server-assigned IDs and verification status.
      dispatch(fetchBankAccounts(partnerCode));
      if (typeof onSaved === 'function') onSaved(result);
    } catch (e) {
      const message = e?.message ?? (typeof e === 'string' ? e : 'unknown error');
      snackbar.error(`Save failed: ${message}`);
    }
  };

  const handleVerify = async (accountId) => {
    if (!accountId) return;
    try {
      await dispatch(verifyBankAccount({ partnerCode, accountId })).unwrap();
      snackbar.success('Verification triggered');
    } catch (e) {
      const message = e?.message ?? 'Verification request failed';
      snackbar.error(message);
    }
  };

  const busy = saving || isSubmitting;

  const bankAccountsWatch = watch('bankAccounts') ?? [];

  // Top-level array-level error (e.g. "one primary per currency").
  // RHF v7+ with yupResolver surfaces a test() error on the array itself at
  // errors.bankAccounts?.root?.message; individual item errors are indexed.
  const arrayError =
    typeof errors?.bankAccounts?.root?.message === 'string'
      ? errors.bankAccounts.root.message
      : typeof errors?.bankAccounts?.message === 'string'
      ? errors.bankAccounts.message
      : null;

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-label="bank-accounts-section"
    >
      <Stack spacing={4}>
        {/* Header */}
        <Box>
          <Typography variant="h6" gutterBottom>
            Bank Accounts
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 4 — add the settlement and payout bank accounts for this
            partner. At least one account per currency must be marked primary.
            Verification status is updated per row once the account is saved.
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            <strong>Note:</strong> Post-activation changes require dual
            authorisation (deferred to Slice 8 — not yet enforced).
          </Typography>
        </Box>

        {loadingAccounts && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}>
            <CircularProgress size={24} aria-label="loading-bank-accounts" />
          </Box>
        )}

        {arrayError && (
          <Alert severity="error" variant="outlined" aria-label="array-error">
            {arrayError}
          </Alert>
        )}

        {fields.length === 0 && (
          <Alert severity="warning" variant="outlined">
            No bank accounts added yet. Click &ldquo;Add account&rdquo; to add the first entry.
          </Alert>
        )}

        {fields.map((field, index) => {
          const saved = savedAccounts.find((a, i) => i === index) ?? null;
          const accountId = saved?.id ?? null;
          const verificationStatus = saved?.verificationStatus ?? null;
          const verificationDate = saved?.verificationDate ?? null;
          const isVerifying = verifying[accountId] ?? false;
          const rowErrors = errors?.bankAccounts?.[index];
          const isPrimaryValue = bankAccountsWatch[index]?.isPrimary ?? false;

          return (
            <BankAccountRow
              key={field.id}
              index={index}
              control={control}
              register={register}
              errors={rowErrors}
              onRemove={() => remove(index)}
              removable={fields.length > 1}
              isPrimaryValue={isPrimaryValue}
              accountId={accountId}
              verificationStatus={verificationStatus}
              verificationDate={verificationDate}
              isVerifying={isVerifying}
              onVerify={handleVerify}
            />
          );
        })}

        <Box>
          <Button
            type="button"
            variant="outlined"
            startIcon={<AddIcon />}
            onClick={() => append(emptyRow())}
            aria-label="Add account"
          >
            Add account
          </Button>
        </Box>

        {/* Submit */}
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

/** Default values for a new empty bank account row. */
function emptyRow() {
  return {
    currency: '',
    bankName: '',
    bicSwift: '',
    ibanOrAccountNumber: '',
    accountHolderName: '',
    bankCountry: '',
    intermediaryBic: '',
    swiftChargeBearer: 'SHA',
    purpose: 'PAYOUT',
    isPrimary: false,
  };
}

/**
 * A single bank account row in the multi-row editor.
 *
 * @param {object}   props
 * @param {number}   props.index               Row index in the field array.
 * @param {object}   props.control             RHF control object.
 * @param {Function} props.register            RHF register function.
 * @param {object}   [props.errors]            RHF errors for this row.
 * @param {Function} props.onRemove            Called when the delete button is clicked.
 * @param {boolean}  props.removable           Whether the delete button is enabled.
 * @param {boolean}  props.isPrimaryValue      Current isPrimary value (watched).
 * @param {string|null} props.accountId        UUID of the saved account (null if unsaved).
 * @param {string|null} props.verificationStatus Current verification status from the BFF.
 * @param {string|null} props.verificationDate  ISO date of last verification.
 * @param {boolean}  props.isVerifying         Whether a verify request is in-flight.
 * @param {Function} props.onVerify            Called with accountId when "Verify" is clicked.
 */
function BankAccountRow({
  index,
  control,
  register,
  errors,
  onRemove,
  removable,
  isPrimaryValue,
  accountId,
  verificationStatus,
  verificationDate,
  isVerifying,
  onVerify,
}) {
  const prefix = `bankAccounts.${index}`;
  const chipCfg = verificationStatus
    ? (VERIFICATION_STATUS_CONFIG[verificationStatus] ?? { label: verificationStatus, color: 'default' })
    : null;

  return (
    <Box sx={{ mb: 2 }} aria-label={`bank-account-row-${index}`}>
      <Divider textAlign="left" sx={{ mb: 2 }}>
        <Stack direction="row" spacing={1} alignItems="center">
          <Typography variant="overline">Account {index + 1}</Typography>
          {chipCfg && (
            <Chip
              label={chipCfg.label}
              color={chipCfg.color}
              size="small"
              aria-label={`verification-status-${index}`}
            />
          )}
          {verificationDate && (
            <Typography variant="caption" color="text.secondary">
              {new Date(verificationDate).toLocaleDateString()}
            </Typography>
          )}
        </Stack>
      </Divider>

      <Grid container spacing={2} alignItems="flex-start">
        {/* Currency */}
        <Grid item xs={12} md={2}>
          <TextField
            label="Currency"
            fullWidth
            required
            {...register(`${prefix}.currency`)}
            error={!!errors?.currency}
            helperText={errors?.currency?.message ?? 'e.g. USD, KRW'}
            inputProps={{
              'aria-label': `bankAccounts[${index}].currency`,
              style: { textTransform: 'uppercase' },
            }}
            onChange={(e) => {
              e.target.value = e.target.value.toUpperCase();
            }}
          />
        </Grid>

        {/* Bank name */}
        <Grid item xs={12} md={4}>
          <TextField
            label="Bank name"
            fullWidth
            required
            {...register(`${prefix}.bankName`)}
            error={!!errors?.bankName}
            helperText={errors?.bankName?.message ?? 'Full name of the receiving bank'}
            inputProps={{ 'aria-label': `bankAccounts[${index}].bankName` }}
          />
        </Grid>

        {/* BIC/SWIFT */}
        <Grid item xs={12} md={3}>
          <Controller
            name={`${prefix}.bicSwift`}
            control={control}
            render={({ field }) => (
              <TextField
                {...field}
                label="BIC / SWIFT"
                fullWidth
                required
                error={!!errors?.bicSwift}
                helperText={errors?.bicSwift?.message ?? '8 or 11 uppercase chars'}
                inputProps={{
                  'aria-label': `bankAccounts[${index}].bicSwift`,
                  style: { textTransform: 'uppercase' },
                }}
                onChange={(e) => field.onChange(e.target.value.toUpperCase().replace(/\s/g, ''))}
              />
            )}
          />
        </Grid>

        {/* Bank country */}
        <Grid item xs={12} md={3}>
          <Controller
            name={`${prefix}.bankCountry`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.bankCountry} required>
                <InputLabel id={`bank-country-label-${index}`}>Bank country</InputLabel>
                <Select
                  {...field}
                  labelId={`bank-country-label-${index}`}
                  label="Bank country"
                  inputProps={{ 'aria-label': `bankAccounts[${index}].bankCountry` }}
                >
                  {ISO_3166_ALPHA2.map((code) => (
                    <MenuItem key={code} value={code}>
                      {COUNTRY_LABELS[code] ?? code}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.bankCountry?.message ?? 'Country where bank is domiciled'}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Grid>

        {/* IBAN / Account number */}
        <Grid item xs={12} md={6}>
          <TextField
            label="IBAN or account number"
            fullWidth
            required
            {...register(`${prefix}.ibanOrAccountNumber`)}
            error={!!errors?.ibanOrAccountNumber}
            helperText={
              errors?.ibanOrAccountNumber?.message ??
              'IBAN (mod-97 validated) or domestic account number'
            }
            inputProps={{ 'aria-label': `bankAccounts[${index}].ibanOrAccountNumber` }}
          />
        </Grid>

        {/* Account holder name */}
        <Grid item xs={12} md={6}>
          <TextField
            label="Account holder name"
            fullWidth
            required
            {...register(`${prefix}.accountHolderName`)}
            error={!!errors?.accountHolderName}
            helperText={errors?.accountHolderName?.message ?? 'Name as it appears on the account'}
            inputProps={{ 'aria-label': `bankAccounts[${index}].accountHolderName` }}
          />
        </Grid>

        {/* Intermediary BIC */}
        <Grid item xs={12} md={4}>
          <Controller
            name={`${prefix}.intermediaryBic`}
            control={control}
            render={({ field }) => (
              <TextField
                {...field}
                label="Intermediary BIC (optional)"
                fullWidth
                error={!!errors?.intermediaryBic}
                helperText={errors?.intermediaryBic?.message ?? 'Correspondent bank BIC if required'}
                inputProps={{
                  'aria-label': `bankAccounts[${index}].intermediaryBic`,
                  style: { textTransform: 'uppercase' },
                }}
                onChange={(e) =>
                  field.onChange(e.target.value.toUpperCase().replace(/\s/g, ''))
                }
              />
            )}
          />
        </Grid>

        {/* Charge bearer */}
        <Grid item xs={12} md={4}>
          <Controller
            name={`${prefix}.swiftChargeBearer`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.swiftChargeBearer} required>
                <InputLabel id={`charge-bearer-label-${index}`}>Charge bearer</InputLabel>
                <Select
                  {...field}
                  labelId={`charge-bearer-label-${index}`}
                  label="Charge bearer"
                  inputProps={{ 'aria-label': `bankAccounts[${index}].swiftChargeBearer` }}
                >
                  {CHARGE_BEARERS.map((cb) => (
                    <MenuItem key={cb} value={cb}>
                      {CHARGE_BEARER_LABELS[cb] ?? cb}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.swiftChargeBearer?.message ?? 'Who bears SWIFT transaction fees'}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Grid>

        {/* Purpose */}
        <Grid item xs={12} md={4}>
          <Controller
            name={`${prefix}.purpose`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.purpose} required>
                <InputLabel id={`purpose-label-${index}`}>Purpose</InputLabel>
                <Select
                  {...field}
                  labelId={`purpose-label-${index}`}
                  label="Purpose"
                  inputProps={{ 'aria-label': `bankAccounts[${index}].purpose` }}
                >
                  {ACCOUNT_PURPOSES.map((p) => (
                    <MenuItem key={p} value={p}>
                      {ACCOUNT_PURPOSE_LABELS[p] ?? p}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.purpose?.message ?? 'Intended use of this account'}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Grid>

        {/* Is-primary radio */}
        <Grid item xs={12} md={6}>
          <Controller
            name={`${prefix}.isPrimary`}
            control={control}
            render={({ field }) => (
              <FormControl component="fieldset">
                <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block' }}>
                  Primary account for this currency?
                </Typography>
                <RadioGroup
                  row
                  value={field.value ? 'yes' : 'no'}
                  onChange={(e) => field.onChange(e.target.value === 'yes')}
                  aria-label={`bankAccounts[${index}].isPrimary`}
                >
                  <FormControlLabel value="yes" control={<Radio size="small" />} label="Yes" />
                  <FormControlLabel value="no" control={<Radio size="small" />} label="No" />
                </RadioGroup>
              </FormControl>
            )}
          />
        </Grid>

        {/* Verify button + remove button */}
        <Grid
          item
          xs={12}
          md={6}
          sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 1 }}
        >
          {accountId && (
            <Tooltip title={isVerifying ? 'Verifying…' : 'Trigger bank-account verification'}>
              <span>
                <Button
                  type="button"
                  variant="outlined"
                  size="small"
                  startIcon={
                    isVerifying ? (
                      <CircularProgress size={14} color="inherit" />
                    ) : (
                      <VerifiedIcon fontSize="small" />
                    )
                  }
                  onClick={() => onVerify(accountId)}
                  disabled={isVerifying}
                  aria-label={`verify-account-${index}`}
                >
                  Verify
                </Button>
              </span>
            </Tooltip>
          )}

          <Tooltip title={removable ? 'Remove account' : 'Cannot remove the only account'}>
            <span>
              <IconButton
                onClick={onRemove}
                disabled={!removable}
                color="error"
                aria-label={`remove-account-${index}`}
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

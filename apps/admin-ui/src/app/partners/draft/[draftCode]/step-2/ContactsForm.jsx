'use client';

import { useEffect, useMemo } from 'react';
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
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { useAppDispatch, useAppSelector } from '@/store';
import { patchStep2 } from '@/store/draftsSlice';
import { useSnackbar } from '@/components/SnackbarProvider';
import partnerStep2Schema, {
  CONTACT_ROLES,
  CONTACT_ROLE_LABELS,
  REQUIRED_ACTIVATION_ROLES,
} from '@/schemas/partnerStep2Schema';

/**
 * Slice 2 — Step 2 (Contacts) form. Mounted by the wizard shell at
 * /partners/draft/{partnerCode} when cursor === 2.
 *
 * Wire shape (sent on submit, matches BFF DraftPartnerStep2Request):
 *   {
 *     contacts: [
 *       {
 *         role: string,
 *         name: string,
 *         email: string,
 *         phoneE164: string,
 *         isAuthorizedSignatory: boolean,
 *         notes: string|null,
 *       }
 *     ]
 *   }
 *
 * The form renders a variable-length list of contact rows; the operator can
 * add or remove rows freely. A soft-warning chip appears when fewer than 4
 * distinct activation-required roles (OPS_24X7, FINANCE, COMPLIANCE, TECH)
 * are covered; this is advisory — the BFF and Step 8 gate enforcement.
 *
 * Validation lives in {@code @/schemas/partnerStep2Schema}. On successful
 * PATCH, calls {@link onSaved} so the wizard shell advances the cursor.
 *
 * @param {object} props
 * @param {object} props.draft       PartnerView the wizard is editing.
 * @param {string} props.partnerCode URL-pinned identifier; used for the PATCH.
 * @param {(view:object)=>void} [props.onSaved] Called on successful PATCH.
 */
export default function ContactsForm({ draft, partnerCode, onSaved }) {
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
    resolver: yupResolver(partnerStep2Schema),
    defaultValues: defaults,
    mode: 'onBlur',
  });

  const { fields, append, remove } = useFieldArray({
    control,
    name: 'contacts',
  });

  // Repopulate when the draft prop refreshes (e.g. after fetchDraft).
  useEffect(() => {
    reset(defaults);
  }, [defaults, reset]);

  const contacts = watch('contacts') ?? [];

  /**
   * True when the set of contact rows covers fewer than 4 activation-required
   * roles (soft warning — NOT a hard block).
   */
  const missingActivationRoles = REQUIRED_ACTIVATION_ROLES.filter(
    (r) => !contacts.some((c) => c.role === r),
  );
  const showRoleWarning = missingActivationRoles.length > 0;

  const onSubmit = async (values) => {
    if (!partnerCode) {
      snackbar.error('No partner code in URL — cannot save.');
      return;
    }
    const body = {
      contacts: values.contacts.map((c) => ({
        role: c.role,
        name: c.name.trim(),
        email: c.email.trim(),
        phoneE164: c.phoneE164.trim(),
        isAuthorizedSignatory: !!c.isAuthorizedSignatory,
        notes: c.notes && c.notes.trim() !== '' ? c.notes.trim() : null,
      })),
    };
    try {
      const result = await dispatch(
        patchStep2({ partnerCode, body }),
      ).unwrap();
      snackbar.success(`Step 2 saved for ${partnerCode}`);
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
      aria-label="partner-contacts-form"
    >
      <Stack spacing={3}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Contacts
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Step 2 of the partner setup wizard. Add the key contacts for this
            partner. These contacts gate signatory approval and are stored as
            bitemporal regulated data per ADR-010.
          </Typography>
        </Box>

        <Alert
          severity="info"
          variant="outlined"
          icon={<WarningAmberIcon />}
          sx={{ display: showRoleWarning ? 'flex' : 'none' }}
          aria-label="role-coverage-warning"
        >
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Minimum 4 role contacts required before activation
          </Typography>
          {/* Use Box (div) not Typography (p) to avoid invalid div-in-p nesting
              when Chip components are rendered inside. */}
          <Box component="div" sx={{ fontSize: 'body2.fontSize', mt: 0.5 }}>
            The activation gate requires at least one contact for each of:{' '}
            <strong>Ops 24x7</strong>, <strong>Finance</strong>,{' '}
            <strong>Compliance</strong>, <strong>Tech</strong>.{' '}
            {missingActivationRoles.length > 0 && (
              <>
                Missing:{' '}
                {missingActivationRoles.map((r) => (
                  <Chip
                    key={r}
                    label={CONTACT_ROLE_LABELS[r] ?? r}
                    size="small"
                    color="warning"
                    sx={{ mr: 0.5 }}
                  />
                ))}
              </>
            )}
          </Box>
        </Alert>

        {fields.length === 0 && (
          <Alert severity="warning" variant="outlined">
            No contacts added yet. Click &ldquo;Add contact&rdquo; to add the first row.
          </Alert>
        )}

        {fields.map((field, index) => (
          <ContactRow
            key={field.id}
            index={index}
            control={control}
            register={register}
            errors={errors?.contacts?.[index]}
            onRemove={() => remove(index)}
            removable={fields.length > 1}
          />
        ))}

        {typeof errors?.contacts?.message === 'string' && (
          <Typography variant="body2" color="error" role="alert">
            {errors.contacts.message}
          </Typography>
        )}

        <Box>
          <Button
            type="button"
            variant="outlined"
            startIcon={<AddIcon />}
            onClick={() =>
              append({
                role: '',
                name: '',
                email: '',
                phoneE164: '',
                isAuthorizedSignatory: false,
                notes: '',
              })
            }
            aria-label="Add contact"
          >
            Add contact
          </Button>
        </Box>

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
 * A single contact row. Uses a Card-style divider to visually separate rows.
 *
 * @param {object} props
 * @param {number} props.index       Row index in the field array.
 * @param {object} props.control     RHF control.
 * @param {Function} props.register  RHF register.
 * @param {object} [props.errors]    RHF errors for this row.
 * @param {Function} props.onRemove  Callback to remove this row.
 * @param {boolean} props.removable  False when there is only one row left.
 */
function ContactRow({ index, control, register, errors, onRemove, removable }) {
  const prefix = `contacts.${index}`;

  return (
    <Box>
      <Divider textAlign="left" sx={{ mb: 2 }}>
        <Typography variant="overline">Contact {index + 1}</Typography>
      </Divider>

      <Grid container spacing={2} alignItems="flex-start">
        {/* Role */}
        <Grid item xs={12} md={4}>
          <Controller
            name={`${prefix}.role`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.role} required>
                <InputLabel id={`role-label-${index}`}>Role</InputLabel>
                <Select
                  {...field}
                  labelId={`role-label-${index}`}
                  label="Role"
                  inputProps={{ 'aria-label': `contacts[${index}].role` }}
                >
                  {CONTACT_ROLES.map((r) => (
                    <MenuItem key={r} value={r}>
                      {CONTACT_ROLE_LABELS[r] ?? r}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  {errors?.role?.message ?? 'Functional responsibility for this contact.'}
                </FormHelperText>
              </FormControl>
            )}
          />
        </Grid>

        {/* Name */}
        <Grid item xs={12} md={4}>
          <TextField
            label="Full name"
            fullWidth
            required
            {...register(`${prefix}.name`)}
            error={!!errors?.name}
            helperText={errors?.name?.message ?? 'Contact person full name.'}
            inputProps={{ 'aria-label': `contacts[${index}].name` }}
          />
        </Grid>

        {/* Remove button (top-right of row) */}
        <Grid item xs={12} md={4} sx={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Tooltip title={removable ? 'Remove contact' : 'Cannot remove the only contact'}>
            <span>
              <IconButton
                onClick={onRemove}
                disabled={!removable}
                color="error"
                aria-label={`remove-contact-${index}`}
                size="small"
              >
                <DeleteOutlineIcon />
              </IconButton>
            </span>
          </Tooltip>
        </Grid>

        {/* Email */}
        <Grid item xs={12} md={6}>
          <TextField
            label="Email"
            type="email"
            fullWidth
            required
            {...register(`${prefix}.email`)}
            error={!!errors?.email}
            helperText={errors?.email?.message ?? 'Work email address.'}
            inputProps={{ 'aria-label': `contacts[${index}].email` }}
          />
        </Grid>

        {/* Phone */}
        <Grid item xs={12} md={6}>
          <TextField
            label="Phone (E.164)"
            fullWidth
            required
            {...register(`${prefix}.phoneE164`)}
            error={!!errors?.phoneE164}
            helperText={
              errors?.phoneE164?.message ?? 'E.164 format, e.g. +82101234567.'
            }
            inputProps={{ 'aria-label': `contacts[${index}].phoneE164` }}
          />
        </Grid>

        {/* Authorized signatory checkbox */}
        <Grid item xs={12} md={6}>
          <Controller
            name={`${prefix}.isAuthorizedSignatory`}
            control={control}
            render={({ field }) => (
              <FormControlLabel
                control={
                  <Checkbox
                    checked={!!field.value}
                    onChange={(e) => field.onChange(e.target.checked)}
                    inputProps={{
                      'aria-label': `contacts[${index}].isAuthorizedSignatory`,
                    }}
                  />
                }
                label="Authorized signatory"
              />
            )}
          />
        </Grid>

        {/* Notes */}
        <Grid item xs={12}>
          <TextField
            label="Notes (optional)"
            fullWidth
            multiline
            minRows={2}
            {...register(`${prefix}.notes`)}
            error={!!errors?.notes}
            helperText={errors?.notes?.message ?? 'Any additional context about this contact.'}
            inputProps={{ 'aria-label': `contacts[${index}].notes` }}
          />
        </Grid>
      </Grid>
    </Box>
  );
}

/**
 * Build initial form values from the loaded draft. The BFF returns the
 * contacts array on GET /api/v1/admin/partners/{code}/contacts; for a brand-
 * new draft there are no contacts yet so we default to one empty row so the
 * operator sees the form instead of a blank page.
 */
function defaultsFromDraft(draft) {
  const raw = draft?.contacts;
  if (Array.isArray(raw) && raw.length > 0) {
    return {
      contacts: raw.map((c) => ({
        role: c.role ?? '',
        name: c.name ?? '',
        email: c.email ?? '',
        phoneE164: c.phoneE164 ?? '',
        isAuthorizedSignatory: !!c.isAuthorizedSignatory,
        notes: c.notes ?? '',
      })),
    };
  }
  return {
    contacts: [
      {
        role: '',
        name: '',
        email: '',
        phoneE164: '',
        isAuthorizedSignatory: false,
        notes: '',
      },
    ],
  };
}

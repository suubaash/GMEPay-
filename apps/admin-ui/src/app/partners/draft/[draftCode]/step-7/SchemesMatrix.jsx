'use client';

import { useState } from 'react';
import { Controller, useFieldArray } from 'react-hook-form';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import SettingsIcon from '@mui/icons-material/Settings';

/**
 * The canonical set of scheme IDs this wizard manages. New schemes are
 * added to this list as the platform onboards them.
 */
export const SCHEME_IDS = [
  'ZEROPAY',
  'BAKONG',
  'NAPAS_247',
  'PROMPT_PAY',
  'FAST_SG',
  'QRIS',
  'KHQR',
];

export const DIRECTIONS = ['INBOUND', 'OUTBOUND', 'BOTH'];
export const ROLES = ['ACQUIRER', 'ISSUER', 'BOTH'];
export const APPROVAL_METHODS = ['CONFIRMATION', 'SILENT'];
export const PARTNER_TYPE_CHARS = ['D', 'I'];

/**
 * Returns the default row for a scheme (all disabled).
 */
export function defaultSchemeRow(schemeId) {
  return {
    schemeId,
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

/**
 * Default initial value for the schemes field-array — one row per scheme.
 */
export function defaultSchemesValue() {
  return SCHEME_IDS.map(defaultSchemeRow);
}

/**
 * ZeroPay drill-down dialog. Shown when "Configure…" is clicked for ZEROPAY row.
 *
 * @param {object}   props
 * @param {boolean}  props.open         Whether the dialog is open.
 * @param {Function} props.onClose      Called to close without saving.
 * @param {number}   props.index        Field-array index of the ZEROPAY row.
 * @param {object}   props.control      RHF control.
 * @param {Function} props.register     RHF register.
 * @param {object}   [props.errors]     RHF errors scoped to schemes[index].
 * @param {boolean}  props.enabled      Whether the ZEROPAY scheme is enabled.
 */
function ZeropayDialog({ open, onClose, index, control, register, errors, enabled }) {
  const prefix = `schemes.${index}`;

  const merchantIdEmpty = !errors; // checked live below via watch — see parent
  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="sm"
      fullWidth
      aria-label="zeropay-config-dialog"
    >
      <DialogTitle>ZeroPay configuration</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Merchant ID"
            fullWidth
            required={enabled}
            {...register(`${prefix}.zeropayMerchantId`)}
            error={!!errors?.zeropayMerchantId}
            helperText={errors?.zeropayMerchantId?.message ?? 'ZeroPay merchant ID'}
            inputProps={{ 'aria-label': `schemes.${index}.zeropayMerchantId` }}
          />
          <TextField
            label="Sub-merchant ID"
            fullWidth
            {...register(`${prefix}.zeropaySubMerchantId`)}
            error={!!errors?.zeropaySubMerchantId}
            helperText={errors?.zeropaySubMerchantId?.message ?? 'ZeroPay sub-merchant ID (optional)'}
            inputProps={{ 'aria-label': `schemes.${index}.zeropaySubMerchantId` }}
          />
          <TextField
            label="KFTC institution code"
            fullWidth
            required={enabled}
            {...register(`${prefix}.kftcInstitutionCode`)}
            error={!!errors?.kftcInstitutionCode}
            helperText={errors?.kftcInstitutionCode?.message ?? 'Korea Financial Telecommunications & Clearings Institute code'}
            inputProps={{ 'aria-label': `schemes.${index}.kftcInstitutionCode` }}
          />

          <Controller
            name={`${prefix}.partnerTypeChar`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.partnerTypeChar}>
                <InputLabel id={`partner-type-char-label-${index}`}>Partner type</InputLabel>
                <Select
                  {...field}
                  value={field.value ?? 'D'}
                  labelId={`partner-type-char-label-${index}`}
                  label="Partner type"
                  inputProps={{ 'aria-label': `schemes.${index}.partnerTypeChar` }}
                >
                  <MenuItem value="D">D — Direct member</MenuItem>
                  <MenuItem value="I">I — Indirect / sub-member</MenuItem>
                </Select>
                <FormHelperText>{errors?.partnerTypeChar?.message}</FormHelperText>
              </FormControl>
            )}
          />

          <Controller
            name={`${prefix}.approvalMethodCpm`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.approvalMethodCpm}>
                <InputLabel id={`approval-cpm-label-${index}`}>Approval method (CPM)</InputLabel>
                <Select
                  {...field}
                  value={field.value ?? 'CONFIRMATION'}
                  labelId={`approval-cpm-label-${index}`}
                  label="Approval method (CPM)"
                  inputProps={{ 'aria-label': `schemes.${index}.approvalMethodCpm` }}
                >
                  {APPROVAL_METHODS.map((m) => (
                    <MenuItem key={m} value={m}>{m}</MenuItem>
                  ))}
                </Select>
                <FormHelperText>{errors?.approvalMethodCpm?.message ?? 'Consumer Presented Mode approval'}</FormHelperText>
              </FormControl>
            )}
          />

          <Controller
            name={`${prefix}.approvalMethodMpm`}
            control={control}
            render={({ field }) => (
              <FormControl fullWidth error={!!errors?.approvalMethodMpm}>
                <InputLabel id={`approval-mpm-label-${index}`}>Approval method (MPM)</InputLabel>
                <Select
                  {...field}
                  value={field.value ?? 'CONFIRMATION'}
                  labelId={`approval-mpm-label-${index}`}
                  label="Approval method (MPM)"
                  inputProps={{ 'aria-label': `schemes.${index}.approvalMethodMpm` }}
                >
                  {APPROVAL_METHODS.map((m) => (
                    <MenuItem key={m} value={m}>{m}</MenuItem>
                  ))}
                </Select>
                <FormHelperText>{errors?.approvalMethodMpm?.message ?? 'Merchant Presented Mode approval'}</FormHelperText>
              </FormControl>
            )}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} aria-label="zeropay-dialog-close">Done</Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Schemes Matrix for Step 7 (Schemes & Corridors).
 *
 * Renders a table — one row per scheme ID (ZEROPAY, BAKONG, …). Columns:
 *   - Enabled (Switch)
 *   - Direction (Select INBOUND/OUTBOUND/BOTH)
 *   - Role (Select ACQUIRER/ISSUER/BOTH)
 *   - Configure… (Button — opens ZeroPay drill-down dialog for ZEROPAY row)
 *
 * Live warning when ZEROPAY is enabled but merchantId or institutionCode
 * is empty.
 *
 * @param {object}   props
 * @param {object}   props.control       RHF control from the parent form.
 * @param {Function} props.register      RHF register.
 * @param {object}   [props.errors]      RHF errors scoped to schemes array.
 * @param {Function} [props.onSchemeChange]  Called when active scheme changes (for OperatingHoursPreview).
 */
export default function SchemesMatrix({ control, register, errors, onSchemeChange }) {
  const { fields } = useFieldArray({ control, name: 'schemes' });
  const [zeropayDialogIndex, setZeropayDialogIndex] = useState(null);

  // We use Controller to read enabled/merchant fields for the warning check.
  return (
    <Box aria-label="schemes-matrix-section">
      <Stack spacing={2}>
        <Box>
          <Typography variant="h6" gutterBottom>
            Scheme enrollments
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Enable the payment schemes this partner will participate in and
            configure direction, role, and scheme-specific settings.
          </Typography>
        </Box>

        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" aria-label="schemes-matrix-table">
            <TableHead>
              <TableRow>
                <TableCell>Scheme</TableCell>
                <TableCell>Enabled</TableCell>
                <TableCell>Direction</TableCell>
                <TableCell>Role</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {fields.map((field, index) => (
                <SchemeRow
                  key={field.id}
                  index={index}
                  schemeId={field.schemeId}
                  control={control}
                  register={register}
                  errors={errors?.[index]}
                  onConfigure={() => setZeropayDialogIndex(index)}
                  onSchemeChange={onSchemeChange}
                />
              ))}
            </TableBody>
          </Table>
        </Box>
      </Stack>

      {/* ZeroPay drill-down dialog */}
      {zeropayDialogIndex !== null && (
        <Controller
          name={`schemes.${zeropayDialogIndex}`}
          control={control}
          render={({ field: rowField }) => {
            const enabled = !!rowField.value?.enabled;
            const merchantId = rowField.value?.zeropayMerchantId ?? '';
            const institutionCode = rowField.value?.kftcInstitutionCode ?? '';
            const showWarning = enabled && (!merchantId || !institutionCode);
            return (
              <>
                {showWarning && (
                  <Alert severity="warning" aria-label="zeropay-config-warning" sx={{ mt: 1 }}>
                    ZEROPAY is enabled but merchant ID or KFTC institution code is missing.
                  </Alert>
                )}
                <ZeropayDialog
                  open
                  onClose={() => setZeropayDialogIndex(null)}
                  index={zeropayDialogIndex}
                  control={control}
                  register={register}
                  errors={errors?.[zeropayDialogIndex]}
                  enabled={enabled}
                />
              </>
            );
          }}
        />
      )}

      {/* Inline warning when dialog is closed but ZEROPAY still misconfigured */}
      <ZeropayInlineWarning
        control={control}
        schemeIndex={fields.findIndex((f) => f.schemeId === 'ZEROPAY')}
      />
    </Box>
  );
}

/**
 * Watches the ZEROPAY row and renders an inline warning when the dialog is
 * not open but merchantId or institutionCode is missing while enabled.
 */
function ZeropayInlineWarning({ control, schemeIndex }) {
  if (schemeIndex < 0) return null;

  return (
    <Controller
      name={`schemes.${schemeIndex}`}
      control={control}
      render={({ field }) => {
        const row = field.value ?? {};
        const show = !!row.enabled && (!row.zeropayMerchantId || !row.kftcInstitutionCode);
        if (!show) return null;
        return (
          <Alert severity="warning" sx={{ mt: 1 }} aria-label="zeropay-inline-warning">
            ZEROPAY is enabled — please configure merchant ID and KFTC institution code.
          </Alert>
        );
      }}
    />
  );
}

/**
 * Single row in the schemes matrix table.
 */
function SchemeRow({ index, schemeId, control, register, errors, onConfigure, onSchemeChange }) {
  const isZeropay = schemeId === 'ZEROPAY';

  return (
    <TableRow hover>
      <TableCell>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {schemeId}
        </Typography>
      </TableCell>

      {/* Enabled switch */}
      <TableCell>
        <Controller
          name={`schemes.${index}.enabled`}
          control={control}
          render={({ field }) => (
            <FormControlLabel
              control={
                <Switch
                  checked={!!field.value}
                  onChange={(e) => {
                    field.onChange(e.target.checked);
                    if (e.target.checked && typeof onSchemeChange === 'function') {
                      onSchemeChange(schemeId);
                    }
                  }}
                  inputProps={{ 'aria-label': `schemes.${index}.enabled` }}
                />
              }
              label=""
            />
          )}
        />
      </TableCell>

      {/* Direction */}
      <TableCell>
        <Controller
          name={`schemes.${index}.direction`}
          control={control}
          render={({ field }) => (
            <FormControl size="small" fullWidth error={!!errors?.direction}>
              <Select
                {...field}
                value={field.value ?? 'OUTBOUND'}
                inputProps={{ 'aria-label': `schemes.${index}.direction` }}
                sx={{ minWidth: 130 }}
              >
                {DIRECTIONS.map((d) => (
                  <MenuItem key={d} value={d}>{d}</MenuItem>
                ))}
              </Select>
              {errors?.direction && (
                <FormHelperText>{errors.direction.message}</FormHelperText>
              )}
            </FormControl>
          )}
        />
      </TableCell>

      {/* Role */}
      <TableCell>
        <Controller
          name={`schemes.${index}.role`}
          control={control}
          render={({ field }) => (
            <FormControl size="small" fullWidth error={!!errors?.role}>
              <Select
                {...field}
                value={field.value ?? 'ACQUIRER'}
                inputProps={{ 'aria-label': `schemes.${index}.role` }}
                sx={{ minWidth: 120 }}
              >
                {ROLES.map((r) => (
                  <MenuItem key={r} value={r}>{r}</MenuItem>
                ))}
              </Select>
              {errors?.role && (
                <FormHelperText>{errors.role.message}</FormHelperText>
              )}
            </FormControl>
          )}
        />
      </TableCell>

      {/* Configure button (ZeroPay only) */}
      <TableCell>
        {isZeropay && (
          <Button
            size="small"
            startIcon={<SettingsIcon />}
            onClick={onConfigure}
            aria-label={`configure-scheme-${index}`}
          >
            Configure…
          </Button>
        )}
      </TableCell>
    </TableRow>
  );
}

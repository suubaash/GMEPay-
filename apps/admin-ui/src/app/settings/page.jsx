'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Box,
  Button,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import SaveIcon from '@mui/icons-material/Save';
import { adminApi } from '@/api/client';
import ErrorAlert from '@/components/ErrorAlert';
import EmptyState from '@/components/EmptyState';
import LoadingSkeleton from '@/components/LoadingSkeleton';
import { useSnackbar } from '@/components/SnackbarProvider';

/**
 * Platform Settings — the editable registry of platform tunables (owner Goal #3:
 * hard-coded values become configurable settings in the admin UI).
 *
 * Operators see every tunable (key, plain-language description, type, current
 * value + who touched it last) and can edit any value inline. A per-row Save
 * PUTs the new value; each edit is audited server-side.
 *
 * Reads  GET /v1/admin/settings              (adminApi.listSettings)
 * Writes PUT /v1/admin/settings/{key} {value} (adminApi.updateSetting)
 *
 * `value` is always a string on the wire. For a NUMBER setting we validate the
 * draft is numeric client-side before enabling Save (the backend coerces + 400s
 * anyway, but this keeps the UX tight).
 */

const isNumberType = (t) => String(t ?? '').trim().toUpperCase() === 'NUMBER';

/** A numeric string is valid iff it parses to a finite number and is non-empty. */
function isNumeric(v) {
  if (v === null || v === undefined) return false;
  const s = String(v).trim();
  if (s === '') return false;
  return Number.isFinite(Number(s));
}

/** Format an ISO instant for the "Last updated" column; graceful on bad input. */
function fmtTime(iso) {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(iso));
  } catch {
    return String(iso);
  }
}

/** One editable settings row: text/number field + a dirty-gated Save button. */
function SettingRow({ setting, onSaved, snackbar }) {
  const [draft, setDraft] = useState(setting.value ?? '');
  const [saving, setSaving] = useState(false);

  // Re-sync the draft if the row is refreshed from the server (e.g. a save
  // elsewhere or a reload). Only the current row's value drives this.
  useEffect(() => {
    setDraft(setting.value ?? '');
  }, [setting.value]);

  const numeric = isNumberType(setting.valueType);
  const original = setting.value ?? '';
  const dirty = String(draft) !== String(original);
  const valid = numeric ? isNumeric(draft) : true;
  const canSave = dirty && valid && !saving;

  const save = async () => {
    setSaving(true);
    try {
      const updated = await adminApi.updateSetting(setting.key, String(draft));
      // Prefer the server's echoed row; fall back to an optimistic merge.
      onSaved(updated ?? { ...setting, value: String(draft) });
      snackbar.success(`Saved “${setting.key}”.`);
    } catch (e) {
      snackbar.error(
        e && e.message ? e.message : `Couldn’t save “${setting.key}”.`,
      );
      // Keep the edit so the operator can retry / correct it.
    } finally {
      setSaving(false);
    }
  };

  return (
    <TableRow hover>
      <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>
        {setting.key}
      </TableCell>
      <TableCell sx={{ color: 'text.secondary', maxWidth: 320 }}>
        {setting.description ?? '—'}
      </TableCell>
      <TableCell sx={{ whiteSpace: 'nowrap' }}>
        {setting.valueType ?? 'STRING'}
      </TableCell>
      <TableCell sx={{ minWidth: 200 }}>
        <TextField
          size="small"
          fullWidth
          type={numeric ? 'number' : 'text'}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          error={dirty && !valid}
          helperText={dirty && !valid ? 'Must be a number' : ''}
          inputProps={{ 'aria-label': `value for ${setting.key}` }}
        />
      </TableCell>
      <TableCell sx={{ whiteSpace: 'nowrap' }}>
        <Typography variant="body2">{fmtTime(setting.updatedAt)}</Typography>
        <Typography variant="caption" color="text.secondary">
          {setting.updatedBy ? `by ${setting.updatedBy}` : '—'}
        </Typography>
      </TableCell>
      <TableCell align="right">
        <Tooltip
          title={
            !dirty
              ? 'No changes to save'
              : !valid
                ? 'Enter a valid number first'
                : 'Save this value'
          }
        >
          {/* Span wrapper: MUI Tooltip needs a hoverable child; disabled
              buttons swallow mouse events without it. */}
          <span>
            <Button
              size="small"
              variant="contained"
              startIcon={<SaveIcon />}
              disabled={!canSave}
              aria-label={`save ${setting.key}`}
              onClick={save}
            >
              Save
            </Button>
          </span>
        </Tooltip>
      </TableCell>
    </TableRow>
  );
}

export default function SettingsPage() {
  const snackbar = useSnackbar();
  const [settings, setSettings] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    adminApi
      .listSettings()
      .then((res) => setSettings(Array.isArray(res) ? res : []))
      .catch((e) =>
        setError(
          e && e.message
            ? e.message
            : 'Couldn’t load platform settings — is the fleet running?',
        ),
      )
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Merge a saved row back into local state by key so the "Last updated"
  // column and baseline (dirty) tracking reflect the server truth.
  const handleSaved = useCallback((updated) => {
    setSettings((prev) =>
      (prev ?? []).map((s) => (s.key === updated.key ? { ...s, ...updated } : s)),
    );
  }, []);

  const rows = settings ?? [];

  return (
    <Box>
      <Typography variant="h1" gutterBottom>
        Platform Settings
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mb: 2 }}>
        Change platform values without a redeploy. Edits are audited.
      </Typography>

      <ErrorAlert
        message={error}
        onRetry={load}
        title="Couldn’t load platform settings — is the fleet running?"
      />

      {loading && !settings ? (
        <LoadingSkeleton variant="table" rows={6} />
      ) : rows.length === 0 ? (
        !error ? (
          <Paper variant="outlined">
            <EmptyState
              heading="No platform settings"
              description="No configurable tunables are registered yet."
            />
          </Paper>
        ) : null
      ) : (
        <TableContainer component={Paper}>
          <Table aria-label="platform settings">
            <TableHead>
              <TableRow>
                <TableCell>Key</TableCell>
                <TableCell>Description</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Value</TableCell>
                <TableCell>Last updated</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((setting) => (
                <SettingRow
                  key={setting.key}
                  setting={setting}
                  onSaved={handleSaved}
                  snackbar={snackbar}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
}

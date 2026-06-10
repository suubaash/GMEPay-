'use client';

import { Box, TextField } from '@mui/material';

export interface DateRangePickerProps {
  /** ISO date (YYYY-MM-DD) or "" when unset. */
  from: string;
  to: string;
  onChange: (next: { from: string; to: string }) => void;
  fromLabel?: string;
  toLabel?: string;
  /** Optional max-date for both inputs (e.g. today). */
  max?: string;
  disabled?: boolean;
}

/**
 * Minimal two-field date-range picker built on plain
 * `<TextField type="date">` — no extra date-picker dep required.
 *
 * Used by the Revenue page (`from`/`to` query params on /v1/admin/revenue/*).
 * Both fields are controlled by the parent; we don't validate ordering here
 * because the BFF will reject out-of-order ranges with a 400.
 */
export default function DateRangePicker({
  from,
  to,
  onChange,
  fromLabel = 'From',
  toLabel = 'To',
  max,
  disabled,
}: DateRangePickerProps) {
  return (
    <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
      <TextField
        type="date"
        label={fromLabel}
        size="small"
        value={from}
        disabled={disabled}
        onChange={(e) => onChange({ from: e.target.value, to })}
        InputLabelProps={{ shrink: true }}
        inputProps={{ max, 'aria-label': fromLabel }}
      />
      <TextField
        type="date"
        label={toLabel}
        size="small"
        value={to}
        disabled={disabled}
        onChange={(e) => onChange({ from, to: e.target.value })}
        InputLabelProps={{ shrink: true }}
        inputProps={{ max, min: from || undefined, 'aria-label': toLabel }}
      />
    </Box>
  );
}

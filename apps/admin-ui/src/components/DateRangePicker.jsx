'use client';

import { Box, TextField } from '@mui/material';

/**
 * DateRangePicker — two `<TextField type="date">` fields wired together.
 *
 * Props:
 *   from:       string  (YYYY-MM-DD, "" when unset)
 *   to:         string
 *   onChange:   (next:{from, to}) => void
 *   fromLabel:  string  (default 'From')
 *   toLabel:    string  (default 'To')
 *   max:        string  (max selectable date, e.g. today)
 *   disabled:   boolean
 *
 * Used by the Revenue page (`from`/`to` query params on /v1/admin/revenue/*).
 */
export default function DateRangePicker({
  from,
  to,
  onChange,
  fromLabel = 'From',
  toLabel = 'To',
  max,
  disabled,
}) {
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

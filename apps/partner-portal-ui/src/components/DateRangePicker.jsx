'use client';
import * as React from 'react';
import Box from '@mui/material/Box';
import TextField from '@mui/material/TextField';

/**
 * DateRangePicker — two `<TextField type="date">` fields wired together.
 *
 * Built on native HTML date inputs so the Partner Portal doesn't pull in
 * `@mui/x-date-pickers` (forbidden by the locked stack). Behaves identically
 * to the admin-ui DateRangePicker so both apps look the same.
 *
 * Props:
 *   from:       string  (YYYY-MM-DD, "" when unset)
 *   to:         string
 *   onChange:   (next:{ from, to }) => void
 *   fromLabel:  string  (default 'From')
 *   toLabel:    string  (default 'To')
 *   max:        string  (max selectable date, e.g. today)
 *   disabled:   boolean
 */
export default function DateRangePicker({
  from,
  to,
  onChange,
  fromLabel = 'From',
  toLabel = 'To',
  max,
  disabled
}) {
  return (
    <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
      <TextField
        type="date"
        label={fromLabel}
        size="small"
        value={from ?? ''}
        disabled={disabled}
        onChange={(e) => onChange({ from: e.target.value, to: to ?? '' })}
        InputLabelProps={{ shrink: true }}
        inputProps={{ max, 'aria-label': fromLabel, 'data-testid': 'date-from' }}
      />
      <TextField
        type="date"
        label={toLabel}
        size="small"
        value={to ?? ''}
        disabled={disabled}
        onChange={(e) => onChange({ from: from ?? '', to: e.target.value })}
        InputLabelProps={{ shrink: true }}
        inputProps={{
          max,
          min: from || undefined,
          'aria-label': toLabel,
          'data-testid': 'date-to'
        }}
      />
    </Box>
  );
}

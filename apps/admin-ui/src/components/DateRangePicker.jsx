'use client';

import { Box } from '@mui/material';
import DateField, { DATE_FLOOR, todayISO } from './DateField';

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
  // Range pickers cover historical data: floor at DATE_FLOOR, cap at today unless
  // a caller passes a wider ceiling, and bind the two fields so from <= to.
  const ceiling = max || todayISO();
  return (
    <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
      <DateField
        label={fromLabel}
        size="small"
        value={from}
        disabled={disabled}
        onChange={(e) => onChange({ from: e.target.value, to })}
        min={DATE_FLOOR}
        max={to || ceiling}
        inputProps={{ 'aria-label': fromLabel }}
      />
      <DateField
        label={toLabel}
        size="small"
        value={to}
        disabled={disabled}
        onChange={(e) => onChange({ from, to: e.target.value })}
        min={from || DATE_FLOOR}
        max={ceiling}
        inputProps={{ 'aria-label': toLabel }}
      />
    </Box>
  );
}
